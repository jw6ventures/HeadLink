#!/bin/sh
# Regenerates core/aap-proto/src/generated from protocol/upstream/open-android-auto with
# the pinned protoc and prost-build, or with --check verifies the checked-in files are
# current. Offline after tools/fetch-deps.sh.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
PROTOC_ZIP=protoc-36.1-linux-x86_64.zip
PROTOC_SHA256=c4bc672d9d49214dc8cafdceadf4df92182d6ca8e3ec65a56b2d7de5602669b4
podman_run --network=none "$RUST_IMAGE" sh -ec '
    echo "$1  /cache/sources/$2" | sha256sum -c --quiet -
    protoc=$(mktemp -d)
    unzip -q "/cache/sources/$2" -d "$protoc"
    PROTOC=$protoc/bin/protoc PROTOC_INCLUDE=$protoc/include \
        cargo run --quiet --manifest-path tools/proto-gen/Cargo.toml --offline --locked \
        --target-dir target/proto-gen -- $3
    rm -rf "$protoc"
  ' proto-gen "$PROTOC_SHA256" "$PROTOC_ZIP" "${1:-}"
