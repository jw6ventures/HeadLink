#!/bin/sh
# Host JVM tests of compiled app classes on the pinned JDK (HotSpot). Runs inside the
# Android image after Gradle has compiled the app:
# * JniContractTest (-Xcheck:jni): release Kotlin NativeBridge/SessionCallback with a
#   host x86_64 build of libaap_jni.
# * ReportStoreTest, TrustStoreTest, and DemoSceneTest: the pure-JVM saved-report and
#   head-unit pin stores and the projected scene's layout and tap rules.
# * DashboardTest: the P5.5 dashboard's layout, media hit testing, sensor scale
#   inference, the map pane's pan/pinch/twist arithmetic and its floating controls, the
#   touch event's pointer format, and what its report may contain.
# * RoutingTest: P6.3's tile naming, profile copy, checked .rd5 import and routing-thread
#   rules over the vendored BRouter jar. ROUTING_SEGMENTS=<dir holding W75_N40.rd5> adds
#   real routes; the tile is 119 MB and not in the repository, so by default it is skipped
#   and the summary line says so.
# * DestinationTest: P6.4's reading of what OsmAnd and other apps share — geo: URIs,
#   OsmAnd's message text and links, bare pairs — its refusals, and the swapped-sign hint.
#   Written against OsmAnd's source; only a real share on the phone confirms the strings.
# * DropFolderTest: which dropped files are taken, waited for or left, and the map
#   import's refusal of a file cut short. The document API itself needs a device.
# * MapReadGuardTest: the map's retry of tiles VTM read badly and reported as loaded.
#   MAP_FILE=<Mapsforge file covering 42.42,-71.21> adds a pass through VTM's own reader;
#   without it the summary line says that pass was skipped.
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
java -cp "$out:$classes:$stdlib:$brouter" RoutingTest "${ROUTING_SEGMENTS:-}"
java -cp "$out:$classes:$stdlib:$brouter" GuidanceTest "${ROUTING_SEGMENTS:-}"
java -cp "$out:$classes:$stdlib:$brouter" DropFolderTest
java -cp "$out:$classes:$stdlib:$brouter" DestinationTest
java -cp "$out:$classes:$stdlib:$vtm" MapReadGuardTest "${MAP_FILE:-}"
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
