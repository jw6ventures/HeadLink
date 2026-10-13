#!/bin/sh
# Runs WirelessProbeTest (W0, docs/wireless-investigation.md) against the release Kotlin
# classes, in the pinned Android image. Build first with tools/android-build.sh, which also
# compiles this test with the others under tools/jni-host-test.
#
# Kept apart from tools/jni-host-test.sh only while the wireless branch is exploratory;
# when it merges, this becomes one more `java ... WirelessProbeTest` line there.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
podman_run --network=none \
  -v "$CACHE_ROOT/gradle:/cache/gradle:ro" -e GRADLE_USER_HOME=/cache/gradle \
  "$ANDROID_IMAGE" sh -ec '
    classes=app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes
    [ -f "$classes/org/openautoandroid/source/WirelessProbe.class" ] \
        || { echo "wireless-probe-test: build the app first" >&2; exit 1; }
    stdlib=$(find "$GRADLE_USER_HOME/caches/modules-2" -name kotlin-stdlib-2.2.10.jar | head -n 1)
    [ -n "$stdlib" ] || { echo "wireless-probe-test: kotlin-stdlib 2.2.10 not in the Gradle cache" >&2; exit 1; }
    out=target/wireless-probe-test
    rm -rf "$out"
    mkdir -p "$out"
    javac -encoding UTF-8 -Xlint:all -Werror -d "$out" -cp "$classes:$stdlib" tools/jni-host-test/WirelessProbeTest.java
    java -cp "$out:$classes:$stdlib" WirelessProbeTest
  '
