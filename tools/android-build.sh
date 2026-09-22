#!/bin/sh
# Cross-builds the pinned OpenSSL and libaap_jni.so for arm64-v8a, packages debug and
# release APKs, runs lint, verifies packaging invariants, and runs the JVM-hosted JNI
# contract test inside the pinned Android image.
#
# Also writes app-release-debugsigned.apk: the release APK, byte-for-byte the same code,
# signed with the local debug key so it installs over the debug build with
# `adb install -r` and keeps the app's data. Use it on the phone and in the vehicle: ART
# runs a debuggable app largely unoptimised, and routing measured thirty times slower in
# the debug build (docs/p6-3a-results.md §3.5). The emulator lab still needs the debug
# APK. The unsigned release APK stays the reproducible artifact; this one is derived.
# Offline by default. Pass --online once to fill the Gradle cache; every downloaded
# artifact is checked against gradle/verification-metadata.xml.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
mode=${1:-}
gradle_offline=
set --
if [ "$mode" != --online ]; then
    gradle_offline=--offline
    set -- --network=none
fi
podman_run "$@" \
  -v "$CACHE_ROOT/gradle:/cache/gradle" -e GRADLE_USER_HOME=/cache/gradle \
  -v "$CACHE_ROOT/android-user:/cache/android-user" -e ANDROID_USER_HOME=/cache/android-user \
  -e GRADLE_OFFLINE="$gradle_offline" \
  "$ANDROID_IMAGE" sh -ec '
    echo "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d  gradle/wrapper/gradle-wrapper.jar" \
      | sha256sum -c -
    tools/openssl-build.sh aarch64-linux-android
    tools/openssl-build.sh x86_64-unknown-linux-gnu
    . tools/openssl-env.sh
    cargo clippy -p aap-jni --target aarch64-linux-android --offline --locked -- -D warnings
    cargo build -p aap-jni --release --target aarch64-linux-android --offline --locked
    rm -rf target/android-jniLibs
    mkdir -p target/android-jniLibs/arm64-v8a
    cp target/aarch64-linux-android/release/libaap_jni.so target/android-jniLibs/arm64-v8a/
    ./gradlew --no-daemon $GRADLE_OFFLINE :app:assembleDebug :app:assembleRelease :app:lintDebug
    tools/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
    tools/verify-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk
    release=app/build/outputs/apk/release/app-release-debugsigned.apk
    apksigner sign --ks "$ANDROID_USER_HOME/debug.keystore" --ks-key-alias androiddebugkey \
        --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false \
        --out "$release" app/build/outputs/apk/release/app-release-unsigned.apk
    tools/verify-apk.sh "$release"
    ! aapt2 dump badging "$release" | grep -q "^application-debuggable" \
        || { echo "android-build: $release is debuggable" >&2; exit 1; }
    tools/jni-host-test.sh
  '
