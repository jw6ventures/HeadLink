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
# * RoutingTest: P6.3's tile naming, profile copy, checked .rd5 import and routing-thread
#   rules over the vendored BRouter jar. ROUTING_SEGMENTS=<dir holding W75_N40.rd5> adds
#   real routes; the tile is 119 MB and not in the repository, so by default it is skipped
#   and the summary line says so.
# * RouteOptionsTest: Maps & routing -> Route preferences: the profile parameters each
#   switch sends, repair, labels, the time adjustment, the stored keys, and Learn my pace
#   over synthetic drives. ROUTING_SEGMENTS with W100_N30.rd5 adds Austin routes (Avoid toll
#   roads leaves SH 130, 56 against 75 mph); with it or W75_N40.rd5, that each route's
#   switches reach BRouter even when its profile cache would have reused the last ones.
# * RoutePreviewTest: the route preview's request and choice files (round trip and
#   refusals), each option's main road, duplicate alternatives and the box the map fits.
#   ROUTING_SEGMENTS with W100_N30.rd5 adds BRouter's real alternatives around Austin.
# * StreetNamesTest: road names read from the map onto a route's maneuvers, the cluster
#   instruction that names them, and the dashboard header's turn line. ROUTING_SEGMENTS
#   with W75_N40.rd5 and MAP_FILE covering Lexington to Boston add a real route.
# * DestinationTest: P6.4's reading of what OsmAnd and other apps share — geo: URIs,
#   OsmAnd's message text and links, bare pairs — its refusals, and the swapped-sign hint.
#   Written against OsmAnd's source; only a real share on the phone confirms the strings.
# * DropFolderTest: which dropped files are taken, waited for or left, and the map
#   import's refusal of a file cut short. The document API itself needs a device.
# * ObfLibraryTest: P11.1's maps folder - which files are opened (no .obf.download, contour,
#   Wikipedia or basemap), a replaced file's new key, regions from OsmAnd's names, which
#   persisted grant each folder keeps, the card's status lines, and Settings -> OsmAnd maps'
#   defaults, repair and stored keys. The grants and the document API need a device.
# * MapReadGuardTest: the map's retry of tiles VTM read badly and reported as loaded.
#   MAP_FILE=<Mapsforge file covering 42.42,-71.21> adds a pass through VTM's own reader;
#   without it the summary line says that pass was skipped.
# * MapThemeTest: P7.6's day map theme has the night theme's rules in the same order,
#   every style it uses, tl_yes toll matching, no route cyan, and legible labels.
# * AppSettingsTest: P7.1's saved settings — that a first launch builds the attempt the
#   screen built before, the stage conditions, the stored form and its repair — the marker
#   colours and day/night cycle, and every branch of the plug-in auto-start rule; since
#   P8.2 the voice guidance keys P8.3's screens build on.
# * SimSignalTest, PlayersTest, NotificationFeedTest and CallStateTest: P7's phone-side
#   backends — the multi-SIM signal rows and network label, the idle media pane's player
#   order, the flex tile's notification filter and feed, and the call state mapping. The
#   Android halves (TelephonyCallback, MediaBrowser, the listener, Telecom) need a phone.
# * StatusTextTest: P7.7's status in words (every row of the plan's §6.2 table and the
#   raw-name fallback), the route summary on Home, and the Setup checklist.
# * VoicePromptsTest: P8.2's turn prompts — when each stage fires over synthetic approaches
#   (city, highway, close turns, joining late, straights, re-routes, arrival, stage and
#   timing settings) and what is said: distances in words and road names, one per row.
# * SpeechQueueTest: P8.1's speech decisions — the engine and Bluetooth gate, a tapped
#   notification against a turn prompt, the second tap, the lead-in, one focus grant for
#   utterances close together and always given back, no prompt in a call — its report's
#   counts, and what a tapped notification is read as. TextToSpeech needs a phone.
# * TrafficClientTest: P9.1's live traffic client and P9.2's response parsing - no request
#   at all while the integration is off or keyless (the injected HTTP layer is never
#   called), exactly the plan's two GETs with gzip, every request counted before it is sent,
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
#   crossings, the cap). ROUTING_SEGMENTS with W100_N30.rd5 adds BRouter's route up I-35E in
#   Dallas; without it the summary line says so.
# * NogoRoutingTest: P9.5's traffic no-go points through RouteRequest and Router into the
#   vendored BRouter, and TrafficCost's delay-to-cost conversion (car-vario's pw/cost0 and
#   the link factor). ROUTING_SEGMENTS with W100_N30.rd5 adds real routes on I-35 in Austin
#   and roads in Dallas and rural Texas: points reach BRouter, a closure blocks, a jam weighs
#   about its delay, a vehicle on a closed road still gets out, nothing leaks into the next
#   request, a jam cannot open a closure on the same link, the plan's overpass and
#   frontage-road cases, and relative timings with 50/200/400 points; with W105_N30.rd5 as
#   well, that the top-speed step-down keeps the points.
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
#   whole, dropped when stale, off, keyless or at the route's end; the time as paced
#   BRouter plus the delay ahead, free flow adding nothing and the pace never counting the
#   delay; no-go points on every request, alternatives search and via route, with closures
#   and jams laid on the route's own line; a closure ahead re-routing once and never on a
#   route that opened closures; the offer at 3 minutes, Keep's suppression until 3 more,
#   withdrawal past the split or when the saving goes, Take; Home's "+N min traffic"; and no
#   place, road, reason or key in the report or event log. ROUTING_SEGMENTS with
#   W100_N30.rd5 adds BRouter's I-35E route in Dallas: a closure laid on it is re-routed
#   round, and a queue brings an offer.
# * WeatherTest: P10's weather from Breezy - the provider's gzipped JSON read (every field,
#   every optional one missing, unknown fields, not gzip, the wrong shape), the location
#   chosen, the stale and far labels at their limits, alerts filtered and ordered, the
#   provider-owner check, the read loop (no read at all with weather off, one at a time, each
#   refusal, major 1, each alert asked to be spoken once), the flex tile's order with every
#   source, the header item at 1080p, 726x480 and 800x480, the view's taps, timeout and call,
#   the alert's dismissal, the alert speech rules, and no place, temperature or headline in
#   a report. It also parses the lab stand-in's own scenarios (tools/breezy-standin/), whose
#   pure Scenarios.java is compiled here too. Fixtures are in Breezy's shape from its source;
#   no real blob from a phone has been read yet (docs/p10-plan.md P10.0).
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
#   counts only. ROUTING_SEGMENTS with W100_N30.rd5 and OBF_DIR with the Austin file add a real
#   search north on I-35 toward Round Rock: gas in the corridor, BRouter's detours, timings.
# * WirelessProbeTest: W0's wireless probe - the verdict for each way an SDP query can end,
#   the UUID labels, and a report with no name or address that ends complete. The query,
#   its broadcast and the Nearby devices prompt need a phone.
# LabDrive.java and LabTraffic.java are compiled here but not run: they are generators run
# by hand, the second writing the P9 lab's stand-in HERE scenarios and traffic drive
# (tools/here-standin/, docs/p9-lab-results.md).
# See each test for scope; neither proves anything about ART or Android storage.
set -eu
classes=app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes
[ -f "$classes/org/openautoandroid/source/NativeBridge.class" ] || { echo "jni-host-test: build the app first" >&2; exit 1; }
brouter=vendor/brouter/build/libs/brouter.jar
[ -f "$brouter" ] || { echo "jni-host-test: $brouter missing; build the app first" >&2; exit 1; }
stdlib=$(find "$GRADLE_USER_HOME/caches/modules-2" -name kotlin-stdlib-2.2.10.jar | head -n 1)
[ -n "$stdlib" ] || { echo "jni-host-test: kotlin-stdlib 2.2.10 not in the Gradle cache" >&2; exit 1; }
vtm=$(find "$GRADLE_USER_HOME/caches/modules-2" -name vtm-0.25.0.jar | head -n 1)
[ -n "$vtm" ] || { echo "jni-host-test: vtm 0.25.0 not in the Gradle cache" >&2; exit 1; }
cargo build -p aap-jni --release --offline --locked
out=target/jni-host-test
rm -rf "$out"
mkdir -p "$out"
javac -encoding UTF-8 -Xlint:all -Werror -d "$out" -cp "$classes:$stdlib:$brouter:$vtm" tools/jni-host-test/*.java \
    tools/breezy-standin/src/org/breezyweather/standin/Scenarios.java
java -cp "$out:$classes:$stdlib" ReportStoreTest
java -cp "$out:$classes:$stdlib" TrustStoreTest
java -cp "$out:$classes:$stdlib" DemoSceneTest
java -cp "$out:$classes:$stdlib" DashboardTest
java -cp "$out:$classes:$stdlib:$brouter" DashboardTripTest
java -cp "$out:$classes:$stdlib" DashboardFlexTest
java -cp "$out:$classes:$stdlib" FavoritesTest
java -cp "$out:$classes:$stdlib" AppSettingsTest
java -cp "$out:$classes:$stdlib:$brouter" RoutingTest "${ROUTING_SEGMENTS:-}"
java -cp "$out:$classes:$stdlib:$brouter" RouteOptionsTest "${ROUTING_SEGMENTS:-}"
java -cp "$out:$classes:$stdlib:$brouter" RoutePreviewTest "${ROUTING_SEGMENTS:-}"
java -cp "$out:$classes:$stdlib:$brouter" GuidanceTest "${ROUTING_SEGMENTS:-}"
java -cp "$out:$classes:$stdlib:$brouter:$vtm" StreetNamesTest "${ROUTING_SEGMENTS:-}" "${MAP_FILE:-}"
java -cp "$out:$classes:$stdlib:$brouter" DropFolderTest
java -cp "$out:$classes:$stdlib" ObfLibraryTest
java -cp "$out:$classes:$stdlib:$brouter" DestinationTest
java -cp "$out:$classes:$stdlib:$vtm" MapReadGuardTest "${MAP_FILE:-}"
java -cp "$out:$classes:$stdlib" SimSignalTest
java -cp "$out:$classes:$stdlib" PlayersTest
java -cp "$out:$classes:$stdlib" NotificationFeedTest
java -cp "$out:$classes:$stdlib" CallStateTest
java -cp "$out:$classes:$stdlib" StatusTextTest
java -cp "$out:$classes:$stdlib" SpeechQueueTest
java -cp "$out:$classes:$stdlib:$brouter" VoicePromptsTest
java -cp "$out" MapThemeTest
java -cp "$out:$classes:$stdlib" TrafficClientTest
java -cp "$out:$classes:$stdlib:$brouter" TrafficMatchTest "${ROUTING_SEGMENTS:-}"
java -cp "$out:$classes:$stdlib:$brouter" NogoRoutingTest "${ROUTING_SEGMENTS:-}"
java -cp "$out:$classes:$stdlib:$brouter:$vtm" DashboardTrafficTest
java -cp "$out:$classes:$stdlib" DashboardPoiTest
java -cp "$out:$classes:$stdlib:$brouter" TrafficFeedTest "${ROUTING_SEGMENTS:-}"
java -cp "$out:$classes:$stdlib" WeatherTest tools/breezy-standin/scenarios
java -cp "$out:$classes:$stdlib" ObfTest "${OBF_DIR:-}"
java -cp "$out:$classes:$stdlib" AddressSearchTest "${OBF_DIR:-}"
java -cp "$out:$classes:$stdlib:$brouter" SearchAheadTest "${ROUTING_SEGMENTS:-}" "${OBF_DIR:-}"
java -cp "$out:$classes:$stdlib" WirelessProbeTest
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
