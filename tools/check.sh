#!/bin/sh
# Rust formatting, lint, tests, and release build with networking disabled, after
# building the pinned host OpenSSL; then checks that generated protobuf code is current.
# Run tools/fetch-deps.sh once first to populate the Cargo cache and source tarballs.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
podman_run --network=none "$RUST_IMAGE" sh -ec '
    tools/openssl-build.sh x86_64-unknown-linux-gnu
    . tools/openssl-env.sh
    cargo fmt --all -- --check
    cargo clippy --workspace --all-targets --offline --locked -- -D warnings
    cargo test --workspace --offline --locked
    cargo build --workspace --release --offline --locked
  '
tools/proto-gen.sh --check
