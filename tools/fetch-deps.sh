#!/bin/sh
# The only networked dependency step: downloads crates pinned by Cargo.lock checksums
# (workspace and tools/proto-gen) and the pinned OpenSSL and protoc archives, verified by
# SHA-256, into the cache used by the offline scripts. Gradle's cache is filled separately
# by `tools/android-build.sh --online`, verified against gradle/verification-metadata.xml.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
podman_run "$RUST_IMAGE" sh -ec '
    cargo fetch --locked
    cargo fetch --locked --manifest-path tools/proto-gen/Cargo.toml
  '
fetch() { # file url sha256
    if ! echo "$3  $CACHE_ROOT/sources/$1" | sha256sum -c --quiet - 2>/dev/null; then
        curl -fsSL --retry 5 -o "$CACHE_ROOT/sources/$1.part" "$2"
        echo "$3  $CACHE_ROOT/sources/$1.part" | sha256sum -c --quiet -
        mv "$CACHE_ROOT/sources/$1.part" "$CACHE_ROOT/sources/$1"
    fi
}
# OpenSSL 3.5.8 LTS: checksum published on github.com and openssl.org; the release
# signature verified against the OpenSSL release key in doc/fingerprints.txt.
fetch openssl-3.5.8.tar.gz \
    https://github.com/openssl/openssl/releases/download/openssl-3.5.8/openssl-3.5.8.tar.gz \
    a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2
# VTM's tessellator for x86_64, so the emulator lab can exercise the owned map (P5.5).
# The shipped APK is arm64-v8a only and takes its copy from the Gradle cache through the
# natives-arm64-v8a dependency; this is the emulator's, injected into a copy of the APK
# by tools/emulator-lab.sh and never packaged. SHA-256 matches the .sha256 published
# beside the artifact on Maven Central.
fetch vtm-android-0.25.0-natives-x86_64.jar \
    https://repo1.maven.org/maven2/org/mapsforge/vtm-android/0.25.0/vtm-android-0.25.0-natives-x86_64.jar \
    29373393635cd117eebed7d97aa1aed1628da73865805b76c89874eef9f6b83a
# protoc 36.1: binary SHA-1 matches Maven Central com.google.protobuf:protoc:4.36.1.
fetch protoc-36.1-linux-x86_64.zip \
    https://github.com/protocolbuffers/protobuf/releases/download/v36.1/protoc-36.1-linux-x86_64.zip \
    c4bc672d9d49214dc8cafdceadf4df92182d6ca8e3ec65a56b2d7de5602669b4
