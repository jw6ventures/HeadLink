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
# * FavoritesTest: the saved places' stored form and bounds; the dashboard tile that lists
#   them is under DashboardTest.
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
javac -Xlint:all -Werror -d "$out" -cp "$classes:$stdlib:$brouter:$vtm" tools/jni-host-test/*.java
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
