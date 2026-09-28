#!/bin/sh
# Builds the emulator lab's stand-in player and caller (tools/lab-player/): an app with a
# launcher activity and a MediaBrowserService that answers the recent-media hint, so the
# idle media pane has a real player to list and Resume can play (P7.5); and a receiver that
# posts a CallStyle call, so the call-notification route has a call to read (P7.9); and one
# that posts and cancels ordinary alerting notifications for the flex tile (P7.8); and a
# silent text-to-speech engine, disabled until the speech script enables it (P8.1). The
# emulator image offers neither: its only browser services are Bluetooth's and a Music app
# with no launcher, and its dialer's call notification is not CallStyle.
#
# Lab only: never shipped and never installed on a phone. Offline, in the pinned Android
# image; the APK goes to $OPENAUTO_CACHE/lab-player/lab-player.apk, never the workspace,
# signed with the same local debug key as the lab build. tools/emulator-lab.sh installs it
# when it is there.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
out=$CACHE_ROOT/lab-player
mkdir -p "$out"
podman_run --network=none \
  -v "$out:/out" \
  -v "$CACHE_ROOT/android-user:/cache/android-user" \
  "$ANDROID_IMAGE" sh -ec '
    sdk=/opt/android-sdk
    bt=$sdk/build-tools/36.1.0
    android=$sdk/platforms/android-36/android.jar
    work=$(mktemp -d)
    mkdir -p "$work/classes" "$work/dex"
    javac -source 11 -target 11 -Xlint:all,-options -Werror -classpath "$android" -d "$work/classes" \
        tools/lab-player/src/org/openautoandroid/labplayer/*.java
    "$bt/d8" --min-api 33 --lib "$android" --output "$work/dex" $(find "$work/classes" -name "*.class")
    "$bt/aapt2" link --manifest tools/lab-player/AndroidManifest.xml -I "$android" \
        --min-sdk-version 33 --target-sdk-version 33 -o "$work/unsigned.apk"
    (cd "$work/dex" && jar uf "$work/unsigned.apk" classes.dex)
    "$bt/zipalign" -f -P 16 4 "$work/unsigned.apk" "$work/aligned.apk"
    "$bt/apksigner" sign --ks /cache/android-user/debug.keystore --ks-pass pass:android \
        --key-pass pass:android --out /out/lab-player.apk "$work/aligned.apk"
    echo "lab-player: $(wc -c < /out/lab-player.apk) bytes"
  '
