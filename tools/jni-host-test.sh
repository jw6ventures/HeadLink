#!/bin/sh
# Host JVM tests of compiled app classes on the pinned JDK (HotSpot). Runs inside the
# Android image after Gradle has compiled the app:
# * JniContractTest (-Xcheck:jni): release Kotlin NativeBridge/SessionCallback with a
#   host x86_64 build of libaap_jni.
# * ReportStoreTest, TrustStoreTest, and DemoSceneTest: the pure-JVM saved-report and
#   head-unit pin stores and the projected scene's layout and tap rules.
# * DashboardTest: the P5.5 dashboard's layout, media hit testing, sensor scale
#   inference, the map pane's pan/pinch/twist arithmetic and its floating controls, the
#   touch event's pointer format, and what its report may contain; since P7.2, P7.3 and
#   P7.6 also the zoom bubbles, the header's icons, signal and battery at 1080p and
#   800x480, day/night resolution before and after the head unit's first NIGHT_DATA,
#   and both chrome palettes' contrast.
# * FavoritesTest: the saved places' stored form and bounds, which destination arrived
#   at is already one of them, and the recent one listed above them; the dashboard tile that lists them is under DashboardTest.
# * DashboardTripTest: P7.4 and P7.5 — the turn card and its maneuver icons, the trip
#   panel in the favourites tile with End route, the marker moved down under the card, the
#   idle media pane's player rows with Resume, and the empty favourites text, at 1080p,
#   Ford's 726x480 and 800x480; since P8.2 the voice toggle beside End route at all three
#   with the map on either side, its press and release, its saved start and warning mark.
# * DashboardFlexTest: P7.8 and P7.9 — the flex tile's priority and label, its notification
#   rows on the favourites' shared scroll rules with the read-aloud stub, the call card on
#   both call routes, the pop-up keypad and its tones, the answer and decline colours in
#   both palettes, and that no report, note or printed snapshot carries a caller, a
#   notification or a keypad digit.
# * RoutePlannerTest: the routing thread over a stand-in RouteEngine - a typed request read
#   as two points or nothing, a newer request superseding one in progress and stopping its
#   search through the handle, an engine's refusal, a crash or running out of memory failing
#   the route and not the thread, and the engine only ever built on the routing thread.
#   OBF_DIR with Us_massachusetts_northamerica.obf adds OsmAnd routes through the planner
#   (across town to Boston Common, the same answer twice, a long search stopped through its
#   handle by a second destination, ending stopped, superseded and with no route) under
#   -Xmx256m, and with the Austin and Waco maps Austin to Waco over two
#   files; without them the summary line says what was skipped.
# * AdaptiveZoomTest: the follow view's zoom by speed band, the waits before zooming out
#   and in, the margin at a band's edge, the glide, that a pinch or +/- holds the zoom
#   until recentre, that a panned map is left alone, and the settings' repair.
# * RouteOptionsTest: Maps & routing -> Route preferences: repair, labels, the time
#   adjustment, the stored keys, Learn my pace and its pace learned across drives (kinds of
#   road, buckets, fading, saved text), over synthetic drives. OBF_DIR with
#   Us_texas_austin_northamerica.obf (and the San Antonio map if present) adds OsmAndRouter
#   routes from Pflugerville to Mustang Ridge: SH 130 by default, Avoid toll roads staying
#   west of it and slower, 56 mph timing it over 10% longer, +20% as 20%, and avoid-tolls
#   right after avoid-highways still avoiding tolls. The profile's parameters are
#   OsmAndRoutingTest's.
# * RoutePreviewTest: the route preview's request and choice files (round trip, request
#   format 6 with 7 flags, a version 5 request refused), each option's main road, duplicate
#   alternatives, the box the map fits, alternatives by number, the via point, the saved
#   route, its remainder and the live alternatives' labels. OBF_DIR with the Austin map adds
#   OsmAndRouter's alternatives 1 and 2 to Round Rock (other roads, none faster than the
#   best); with Us_texas_waco_northamerica.obf too, AlternativeFinder's Austin to Temple
#   alternatives (sharing part of the best, no slower than the router's own first, never
#   repeated).
# * GuidanceTest: turn commands as cluster maneuvers (the pinned numbering), and
#   RouteFollower joining, leaving and arriving; off a route no re-route can replace, the
#   card's no-map or track-only text in place of the old next turn. OBF_DIR with
#   Us_massachusetts_northamerica.obf adds OsmAndRouter's Lexington to Boston route followed
#   end to end (one destination, times never going back) and a re-route, given a heading,
#   from where lab-receiver/drives/lexington.csv leaves it.
# * RoadNamesTest: the road each stretch of a route lies on, OsmAndRouter's label for it over
#   SyntheticObf's roads (name over ref, ref alone, nothing for a blank name), the cluster
#   instruction that names the road turned onto, the road being driven, and the turn card's
#   lines and units. OBF_DIR with Us_massachusetts_northamerica.obf adds OsmAnd's names on a
#   real cross-town route in Lexington to Boston.
# * ObfMapTilesTest: the map drawn from OsmAnd's map data - a tile's 31-bit box, a road's
#   tags, names and pixels, an area's hole, a building kept whole, points in and out of the
#   tile, the sea from coastlines (a coast across the tile, an island, open sea), and that
#   with no OsmAnd map data, or only routing data, no source opens and the pane shows its
#   no-data ground. Objects built in memory; no real file, no drawing.
# * OsmAndRoutingTest: OsmAnd's router (vendor/osmand) behind HeadLink's RouteEngine over
#   SyntheticObf's .obf (rules, the box tree with a pruned child, points, point tags, the
#   string table) - the exit maneuver with its number and signs, no sign on the maneuver at
#   the junction when the route stays on the motorway, an on-ramp driven against its stored
#   order signed by destination:backward, the sign through the follower to the card, cluster and voice and
#   through the route file, the times, a closure and a costly jam moving the route and a
#   cheap one not, an absurd jam still weighed, a destination, via point or dropped minus
#   sign outside the maps refused, a stop only a track reaches and a start, stop or
#   destination with no road but a track near refused, an unsigned exit named by its
#   junction, closures with no way round opened, the second alternative, a stopped search, a
#   track check out of time leaving the route found, no maps, the profile's parameters, and
#   OsmAnd's errors stripped of coordinates. OBF_DIR with
#   Us_texas_austin_northamerica.obf adds a 58 km route with a jam under the phone's 256 MB
#   heap (the plain search's memory failure), and with the San Antonio map from another
#   OsmAnd build, that the hierarchy is skipped. No phone.
# * DestinationTest: P6.4's reading of what OsmAnd and other apps share — geo: URIs,
#   OsmAnd's message text and links, bare pairs — its refusals, and the swapped-sign hint.
#   Written against OsmAnd's source; only a real share on the phone confirms the strings.
# * ObfLibraryTest: P11.1's maps folder - which files are opened (no .obf.download, contour,
#   Wikipedia or basemap), a replaced file's new key, regions from OsmAnd's names, which
#   persisted grant is the maps folder and which the speech model, the card's status lines,
#   and Settings -> OsmAnd maps' defaults, repair and stored keys. The grants and the
#   document API need a device.
# * MapThemeTest: P7.6's day map theme has the night theme's rules in the same order,
#   every style it uses, toll rules on the toll=yes/true values OsmAnd's maps carry and no
#   other, no route cyan, and legible labels.
# * AppSettingsTest: P7.1's saved settings — that a first launch builds the attempt the
#   screen built before, the stage conditions, the stored form and its repair — the marker
#   colours and day/night cycle, and every branch of the plug-in auto-start rule; since
#   P8.2 the voice guidance keys P8.3's screens build on.
# * SimSignalTest, PlayersTest, NotificationFeedTest and CallStateTest: P7's phone-side
#   backends — the multi-SIM signal rows and network label, the idle media pane's player
#   order, the flex tile's notification filter and feed, and the call state mapping. The
#   Android halves (TelephonyCallback, MediaBrowser, the listener, Telecom) need a phone.
# * StatusTextTest: P7.7's status in words (every row of the status table in docs/phone-app.md and the
#   raw-name fallback), the route summary on Home, and the Setup checklist.
# * VoicePromptsTest: P8.2's turn prompts — when each stage fires over synthetic approaches
#   (city, highway, close turns, joining late, straights, re-routes, arrival, stage and
#   timing settings) and what is said: distances in words and road names, one per row.
# * SpeechQueueTest: P8.1's speech decisions — the engine and Bluetooth gate, a tapped
#   notification against a turn prompt, the second tap, the lead-in, one focus grant for
#   utterances close together and always given back, no prompt in a call — its report's
#   counts, and what a tapped notification is read as. TextToSpeech needs a phone.
# * MicCaptureTest: voice input's microphone rules - only the car's hands-free link (SCO)
#   is chosen and a recorder on any other microphone is refused, the 16 kHz mono clip and
#   its 30 s cap, dBFS levels, the end of an utterance after speech and quiet, the
#   no-speech timeout, road noise that never reads as speech, and a report of counts only.
#   Whether a phone and car open the link, and how fast, needs both (BluetoothMic).
# * TrafficClientTest: P9.1's live traffic client and P9.2's response parsing - no request
#   at all while the integration is off or keyless (the injected HTTP layer is never
#   called), exactly the two GETs with gzip, every request counted before it is sent,
#   the monthly cap and its reset on the 1st (UTC), both HERE error shapes with the key never
#   in a message, flow sub-segments and closed stretches without speed, gzip with or without
#   the header, the base-URL override honoured only when debuggable, and that a snapshot is
#   only its own box. Fixtures are hand-written in HERE's shape; HttpURLConnection and the
#   preferences file need a phone.
# * TrafficMatchTest: P9.2 - the boxes asked of HERE around the route ahead (padding, the
#   look-ahead cut at the destination, cells under HERE's 1 degree limit), hand-written HERE
#   stretches laid on a route (same carriageway, the other one 30 m off and reversed, a
#   frontage road, an overpass, subSegments, a no-data gap, closures), the delay, and the
#   nogos' placement (8 m, 30 m end margin, 150 m spacing, hard vs weighted, short closures,
#   crossings, the cap, a jam of touching class-1 and class-2 runs priced each at its own
#   factor). OBF_DIR with Us_texas_dallas_northamerica.obf adds the same on
#   OsmAndRouter's route up I-35E (Stemmons Freeway) in Dallas; without it the summary line
#   says so.
# * NogoRoutingTest: P9.5's traffic no-go points - RouteRequest's validation, TrafficCost's
#   seconds-per-metre weights and class factors, and the route's traffic marks. OBF_DIR with
#   the Austin map adds I-35 through OsmAndRouter, every point placed by TrafficNogos.forRoute:
#   a heavy jam moves the route and a light one leaves it, a closure is driven round, jams at
#   0.3x and 3x the way round stay or leave all of it, the multiples at which the route leaves
#   part and all of each stretch (bracketed, and held to 0.75-1.35 with the delay missed per
#   second added: the table in docs/navigation.md), a vehicle or destination on a closed
#   stretch is still routed and marked, a jam beside a closure does not open it, the overpass
#   and frontage road are not charged, timings with 0/50/200/400 points, and nothing carried
#   to the next request; with the Victoria map, US 290 east of Bastrop, and with the Dallas
#   map, US 75.
# * DashboardTrafficTest: P9.3, P9.4 and P9.6 on the dashboard - which traffic runs colour
#   the route line ahead (slow amber, queuing red, closed dashed dark red; none and free
#   left to the route's own colour), merged and capped, cut at the vehicle in 50 m steps and
#   never drawn on a route they were not matched to; the colours against both map grounds;
#   "Traffic (c) HERE" by the attribution rule and its box clear of both bubble stacks and
#   both cards; the trip panel's "+N min traffic" rounding and fit; the divided turn card in
#   both layouts with Take and Keep at 1080p, Ford's 726x480 and 800x480 both sides; the
#   map's fit to both routes; and the return to following on Take, Keep and withdrawal, but
#   not over the driver's own pan. The VTM layers and the closure's stipple need the lab.
# * TrafficFeedTest: P9.4-P9.6 at run time - the traffic loop run whole with an injected
#   clock, queued threads and the real TrafficClient over a fake HTTP layer: no request
#   without a followed route, with traffic off or keyless (the HTTP layer never called);
#   the first fetch, the refresh cadence, retries, the monthly cap; one fetch after a
#   re-route only when the last box no longer covers it, debounced; a response replaced
#   whole, dropped when stale, off, keyless or at the route's end; the time as the route's
#   paced time plus the delay ahead, free flow adding nothing and the pace never counting the
#   delay; no-go points on every request, alternatives search and via route, with closures
#   and jams laid on the route's own line, the same at the slowest and fastest top speed; a
#   closure ahead re-routing once and never on a route that opened closures, an end closure
#   placed along the line; the offer at 3 minutes, with the bubbles on a look's way round
#   offered alone without ever swapping or taking down the offer up, with them off its
#   final decision (a different way replacing the offer, a closed or missing way round
#   withdrawing it, never a superseded look), a look stopped after its searches ended
#   deciding nothing, Keep's suppression until 3 more, withdrawal past the split or when the
#   saving goes, Take; Home's "+N min traffic"; and no place, road, reason or key in the report or event
#   log. OBF_DIR with Us_texas_dallas_northamerica.obf adds OsmAndRouter's I-35E route in
#   Dallas: a closure laid on it is re-routed round once without opening it, and a queue
#   brings an offer on the engine's way round as soon as that one search returns (its time
#   and the whole look's printed), kept once the bubbles' searches end, whose way round misses it.
# * WeatherTest: P10's weather from Breezy - the provider's gzipped JSON read (every field,
#   every optional one missing, unknown fields, not gzip, the wrong shape), the location
#   chosen, the stale and far labels at their limits, alerts filtered and ordered, the
#   provider-owner check, the read loop (no read at all with weather off, one at a time, each
#   refusal, major 1, each alert asked to be spoken once), the flex tile's order with every
#   source, the header item at 1080p, 726x480 and 800x480, the view's taps, timeout and call,
#   the alert's dismissal, the alert speech rules, and no place, temperature or headline in
#   a report. It also parses the lab stand-in's own scenarios (tools/breezy-standin/), whose
#   pure Scenarios.java is compiled here too. Fixtures are in Breezy's shape from its source;
#   no real blob from a phone has been read yet (docs/verification.md, "Feature status").
# * HomeAssistantTest: the Home Assistant button - the settings' cleaning, repair and stored
#   form, nothing sent while it is off or not set up, a press as exactly one POST with the
#   token only in its header, Check as GETs only, the words for each answer, the button in the
#   header or, where it does not fit, over the map, clear of every other control at 1080p,
#   726x480 and 800x480, and a press handed out once.
# * ObfTest: P11.2-P11.3's OBF reader - the wire walker (varints, zigzag, OsmAnd's wire type 6
#   with 4- and 8-byte big-endian lengths, unknown fields of every wire type), tile arithmetic,
#   the Gas/Food/Shop rules, and a synthetic .obf built from the vendored OBF.proto: contents,
#   cities, a postcode, streets, an interpolation range, the POI box tree pruned by area and
#   category, precisionXY and text-tag names. OBF_DIR=<dir of OsmAnd's Texas .obf files> adds
#   the real Austin, Amarillo and Lubbock files, positions checked against OpenStreetMap in
#   metres, and timings; the files are not in the repository, so by default that is skipped
#   and the summary line says so.
# * AddressSearchTest: P11.4's typed search - the parser's readings of US-style input (house
#   numbers with letters and halves, street type and direction words both ways, FM/RM/SH/US/
#   I-35/CR roads, a city being typed, state and ZIP), and the search over a synthetic file:
#   exact, estimated along odd, even, step and lettered ranges, the closest number on the same
#   side and on another stretch of a split street, streets without numbers, cities, ZIPs,
#   places by name, the order, the state filter, cancellation, a failing file, and a report
#   line with no place in it. OBF_DIR with Us_texas_austin_northamerica.obf adds real searches
#   around the Capitol, distances in metres and host timings; without it the line says so.
# * DashboardPoiTest: P11.5's Gas, Food and Shop buttons along the top of the map pane at 1080p,
#   726x480 and 800x480 both sides, clear of the bubbles, credits and cards; their presses; a
#   tapped marker's card with Go and Close, and the three-place list with its own button that
#   P11.5a uses; the card among the flex tile's sources; the query capped per kind nearest the
#   centre over a fake ObfSource; the refresh cadence and stale results; the open-now line;
#   the marker colours; and no name or place in a report. The VTM markers need the lab.
# * SearchAheadTest: P11.5a's search ahead on the route - places projected onto the route ahead
#   (metres ahead, metres off, side of the road; behind, too close and off-corridor dropped; one
#   per place), the corridor's query boxes ending at the destination, A and B 2 km either side
#   and clamped, the detour's two short routes and arithmetic, the best-stop rule, the words in
#   miles and km with and without a name or a stop, the via point's release, and a report of
#   counts only. OBF_DIR with Us_texas_austin_northamerica.obf adds a real search north on
#   I-35 from downtown Austin toward Round Rock: OsmAndRouter's route, gas in the corridor,
#   the detours to the nearest five, timings.
# * WirelessProbeTest: W0's wireless probe - the verdict for each way an SDP query can end,
#   the UUID labels, and a report with no name or address that ends complete. The query,
#   its broadcast and the Nearby devices prompt need a phone.
# * WirelessBootstrapTest: W1's bootstrap read - framing across RFCOMM chunks, the protobuf
#   reader, the redaction rule, and the session against scripted head units (start first,
#   version first, endpoint only in the version request, pings, silence, a dropped socket),
#   with no key, SSID, BSSID or VIN in the report. The socket and the truck need a phone.
# * WirelessAutoStartTest: W3's start rule - every reason the linked truck connecting over
#   Bluetooth does or does not start wireless projection, in order, across every combination.
#   Android's companion-device wake-up and the link need a phone and the truck.
# * WirelessKeepAwakeTest: the wireless keep-awake's bookkeeping - echo replies split by screen
#   state, bucketed percentiles, and the Wi-Fi link readings with Android's unknowns skipped.
#   The ICMP socket, the screen and the truck need a phone.
# * WirelessStandbyTest: wireless standby's rules - the start reasons, which start requests
#   start projection, the automatic-start loop guard and the reconnect pacing. The held
#   RFCOMM link, the truck's button and the wake lock need a phone and the truck.
# * RecognizerTest (-Xcheck:jni): voice input's recognizer, the release Kotlin WhisperEngine
#   over a host x86_64 build of libwhisper_jni (tools/whisper-build.sh) - a small model in
#   whisper.cpp's real format, written by the test, that says one word; refusals of files that
#   are not models, cut short, all-F32 or hollow; audio too short or long; prompt, cancel,
#   close and reload; text cleaning, the CPU feature check and the report's counts.
#   WHISPER_MODEL=<a ggml model file> adds its load time and 5 s of silence; without it the
#   summary line says so. The model file grant and the arm64 build need a phone.
# LabDrive.java and LabTraffic.java are compiled here but not run: they are generators run
# by hand over an OBF_DIR holding Us_massachusetts_northamerica.obf, routing with
# OsmAndRouter, the second writing the P9 lab's stand-in HERE scenarios and traffic drive
# (tools/here-standin/, docs/verification.md). LabTraffic needs -Xmx512m: OsmAnd's plain
# search from the lab position to Boston Common runs out of its memory guard (a third of
# the heap) at 256 MB, as the app's would without its large heap.
# See each test for scope; neither proves anything about ART or Android storage.
set -eu
classes=app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes
[ -f "$classes/com/jw6ventures/headlink/NativeBridge.class" ] || { echo "jni-host-test: build the app first" >&2; exit 1; }
stdlib=$(find "$GRADLE_USER_HOME/caches/modules-2" -name kotlin-stdlib-2.2.10.jar | head -n 1)
[ -n "$stdlib" ] || { echo "jni-host-test: kotlin-stdlib 2.2.10 not in the Gradle cache" >&2; exit 1; }
vtm=$(find "$GRADLE_USER_HOME/caches/modules-2" -name vtm-0.25.0.jar | head -n 1)
[ -n "$vtm" ] || { echo "jni-host-test: vtm 0.25.0 not in the Gradle cache" >&2; exit 1; }
osmand=vendor/osmand/build/libs/osmand.jar
[ -f "$osmand" ] || { echo "jni-host-test: $osmand missing; build the app first" >&2; exit 1; }
# vendor/osmand's dependencies, and the two it compiles against that Android supplies.
for jar in trove4j-3.0.3 jts-core-1.14.0 openlocationcode-1.0.4 gson-2.8.9 json-20171018 kxml2-2.1.8; do
    found=$(find "$GRADLE_USER_HOME/caches/modules-2" -name "$jar.jar" | head -n 1)
    [ -n "$found" ] || { echo "jni-host-test: $jar not in the Gradle cache" >&2; exit 1; }
    osmand="$osmand:$found"
done
cargo build -p aap-jni --release --offline --locked
out=target/jni-host-test
rm -rf "$out"
mkdir -p "$out"
javac -encoding UTF-8 -Xlint:all -Werror -d "$out" -cp "$classes:$stdlib:$vtm:$osmand" tools/jni-host-test/*.java \
    tools/breezy-standin/src/org/breezyweather/standin/Scenarios.java
java -cp "$out:$classes:$stdlib" ReportStoreTest
java -cp "$out:$classes:$stdlib" TrustStoreTest
java -cp "$out:$classes:$stdlib" DemoSceneTest
java -cp "$out:$classes:$stdlib" DashboardTest
java -cp "$out:$classes:$stdlib" DashboardTripTest
java -cp "$out:$classes:$stdlib" DashboardFlexTest
java -cp "$out:$classes:$stdlib" FavoritesTest
java -cp "$out:$classes:$stdlib" AppSettingsTest
java -Xmx256m -cp "$out:$classes:$stdlib:$osmand" RoutePlannerTest "${OBF_DIR:-}"
java -cp "$out:$classes:$stdlib" AdaptiveZoomTest
java -Xmx256m -cp "$out:$classes:$stdlib:$vtm:$osmand" RouteOptionsTest "${OBF_DIR:-}"
java -Xmx256m -cp "$out:$classes:$stdlib:$vtm:$osmand" RoutePreviewTest "${OBF_DIR:-}"
java -Xmx256m -cp "$out:$classes:$stdlib:$vtm:$osmand" GuidanceTest "${OBF_DIR:-}"
java -Xmx256m -cp "$out:$classes:$stdlib:$osmand" RoadNamesTest "${OBF_DIR:-}"
java -cp "$out:$classes:$stdlib" ObfLibraryTest
java -cp "$out:$classes:$stdlib" DestinationTest
java -cp "$out:$classes:$stdlib" SimSignalTest
java -cp "$out:$classes:$stdlib" PlayersTest
java -cp "$out:$classes:$stdlib" NotificationFeedTest
java -cp "$out:$classes:$stdlib" CallStateTest
java -cp "$out:$classes:$stdlib" StatusTextTest
java -cp "$out:$classes:$stdlib" SpeechQueueTest
java -cp "$out:$classes:$stdlib" MicCaptureTest
java -cp "$out:$classes:$stdlib" VoicePromptsTest
java -cp "$out" MapThemeTest
java -cp "$out:$classes:$stdlib" TrafficClientTest
java -Xmx256m -cp "$out:$classes:$stdlib:$vtm:$osmand" TrafficMatchTest "${OBF_DIR:-}"
java -Xmx256m -cp "$out:$classes:$stdlib:$vtm:$osmand" NogoRoutingTest "${OBF_DIR:-}"
java -cp "$out:$classes:$stdlib:$vtm" DashboardTrafficTest
java -cp "$out:$classes:$stdlib" DashboardPoiTest
java -Xmx256m -cp "$out:$classes:$stdlib:$vtm:$osmand" TrafficFeedTest "${OBF_DIR:-}"
java -cp "$out:$classes:$stdlib" WeatherTest tools/breezy-standin/scenarios
java -cp "$out:$classes:$stdlib" HomeAssistantTest
java -cp "$out:$classes:$stdlib" ObfTest "${OBF_DIR:-}"
java -Xmx256m -cp "$out:$classes:$stdlib:$osmand" OsmAndRoutingTest "${OBF_DIR:-}"
java -cp "$out:$classes:$stdlib:$vtm:$osmand" ObfMapTilesTest
java -cp "$out:$classes:$stdlib" AddressSearchTest "${OBF_DIR:-}"
java -Xmx256m -cp "$out:$classes:$stdlib:$vtm:$osmand" SearchAheadTest "${OBF_DIR:-}"
java -cp "$out:$classes:$stdlib" WirelessProbeTest
java -cp "$out:$classes:$stdlib" WirelessBootstrapTest
java -cp "$out:$classes:$stdlib" WirelessAutoStartTest
java -cp "$out:$classes:$stdlib" WirelessKeepAwakeTest
java -cp "$out:$classes:$stdlib" WirelessStandbyTest
# The x86_64 libwhisper_jni.so from tools/whisper-build.sh; --add-opens reads a descriptor's
# number as Android's ParcelFileDescriptor.getFd() gives it.
status=0
java -Xcheck:jni --add-opens java.base/java.io=ALL-UNNAMED -Djava.library.path=target/whisper/x86_64-linux-gnu \
    -cp "$out:$classes:$stdlib" RecognizerTest "${WHISPER_MODEL:-}" > "$out/speech.txt" 2>&1 || status=$?
if [ "$status" != 0 ] || grep -E 'WARNING in native method|FATAL ERROR' "$out/speech.txt"; then
    cat "$out/speech.txt"
    echo "jni-host-test: speech recognizer failed (exit $status)" >&2
    exit 1
fi
grep '^jni-host-test:' "$out/speech.txt"
status=0
java -Xcheck:jni --add-opens java.base/sun.nio.ch=ALL-UNNAMED -Djava.library.path=target/release \
    -cp "$out:$classes:$stdlib" JniContractTest > "$out/output.txt" 2>&1 || status=$?
if [ "$status" != 0 ] || grep -E 'WARNING in native method|FATAL ERROR' "$out/output.txt"; then
    cat "$out/output.txt"
    echo "jni-host-test: failed (exit $status)" >&2
    exit 1
fi
# The contained callback exceptions are described by the VM; show only the summary.
grep '^jni-host-test:' "$out/output.txt"
