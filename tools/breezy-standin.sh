#!/bin/sh
# Builds the emulator lab's stand-in Breezy Weather (tools/breezy-standin/, P10 plan §8): an
# app with Breezy's package name, its READ_PROVIDER permission and its weather provider,
# serving the scenario files in tools/breezy-standin/scenarios/ (packaged as assets) with
# their times resolved against the moment of each query; and a receiver the lab uses to
# pick the scenario. The emulator image has no Breezy, and P10 reads nothing else.
#
# Lab only: never shipped and NEVER installed on a phone, where org.breezyweather is the
# real Breezy (installing this over it would fail on the signature, and must not be tried).
# Not built by tools/android-build.sh. Offline, in the pinned Android image; the APK goes to
# $OPENAUTO_CACHE/breezy-standin/breezy-standin.apk, never the workspace, signed with the
# same local debug key as the lab build.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
out=$CACHE_ROOT/breezy-standin
mkdir -p "$out"
podman_run --network=none \
  -v "$out:/out" \
  -v "$CACHE_ROOT/android-user:/cache/android-user" \
  "$ANDROID_IMAGE" sh -ec '
    sdk=/opt/android-sdk
    bt=$sdk/build-tools/36.1.0
    android=$sdk/platforms/android-36/android.jar
    work=$(mktemp -d)
    mkdir -p "$work/classes" "$work/dex" "$work/assets"
    javac -encoding UTF-8 -source 11 -target 11 -Xlint:all,-options -Werror -classpath "$android" -d "$work/classes" \
        $(find tools/breezy-standin/src -name "*.java")
    "$bt/d8" --min-api 33 --lib "$android" --output "$work/dex" $(find "$work/classes" -name "*.class")
    # Assets keep the scenarios/ prefix, so Scenarios.java names the same relative paths
    # for the provider (assets) and the host tests (the source tree).
    cp -R tools/breezy-standin/scenarios "$work/assets/scenarios"
    "$bt/aapt2" link --manifest tools/breezy-standin/AndroidManifest.xml -I "$android" \
        -A "$work/assets" --min-sdk-version 33 --target-sdk-version 33 -o "$work/unsigned.apk"
    (cd "$work/dex" && jar uf "$work/unsigned.apk" classes.dex)
    "$bt/zipalign" -f -P 16 4 "$work/unsigned.apk" "$work/aligned.apk"
    "$bt/apksigner" sign --ks /cache/android-user/debug.keystore --ks-pass pass:android \
        --key-pass pass:android --out /out/breezy-standin.apk "$work/aligned.apk"
    echo "breezy-standin: $(wc -c < /out/breezy-standin.apk) bytes, scenarios: $(ls tools/breezy-standin/scenarios | tr "\n" " ")"
  '
