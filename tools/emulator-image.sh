#!/bin/sh
# Networked, optional: downloads Android Emulator 37.2.9 and the AOSP API 36 x86_64 system
# image r02 into the cache (SHA-1 values from Google's repository manifests, SHA-256
# recorded here), then builds localhost/openauto-emulator:smoke for tools/emulator-smoke.sh.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
fetch() { # file url sha256
    if ! echo "$3  $CACHE_ROOT/sources/$1" | sha256sum -c --quiet - 2>/dev/null; then
        # HTTP/1.1: dl.google.com HTTP/2 transfers reset repeatedly from this network.
        curl -fsSL --http1.1 --retry 5 -o "$CACHE_ROOT/sources/$1.part" "$2"
        echo "$3  $CACHE_ROOT/sources/$1.part" | sha256sum -c --quiet -
        mv "$CACHE_ROOT/sources/$1.part" "$CACHE_ROOT/sources/$1"
    fi
}
fetch emulator-linux_x64-16322952.zip \
    https://dl.google.com/android/repository/emulator-linux_x64-16322952.zip \
    703aac00501161e5388e2ca9e3ec8fb5f5b71074b48c35b798d252b0fde81e5a
fetch x86_64-36_r02.zip \
    https://dl.google.com/android/repository/sys-img/android/x86_64-36_r02.zip \
    e1b9d9fb665001ef27b16e57d8762a2d54aec6bff617e17506edb8676667b9da
podman build --security-opt label=disable -v "$CACHE_ROOT/sources:/sources:ro" \
    -f tools/Containerfile.emulator -t localhost/openauto-emulator:smoke .
