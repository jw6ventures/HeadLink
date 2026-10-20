#!/bin/sh
# Signs the reproducible unsigned release APK with the project's release key, inside the
# pinned Android image, and checks the result. Run after tools/android-build.sh.
#
#   RELEASE_KEYSTORE=/path/release.jks RELEASE_KEY_ALIAS=headlink \
#   RELEASE_KEYSTORE_PASSWORD=... RELEASE_KEY_PASSWORD=... tools/sign-release.sh OUT.apk
#
# The passwords reach apksigner through the environment, never a command line. The
# keystore is mounted read-only and never enters the workspace. Like the debug-signed
# copy android-build.sh writes, the signed APK is derived: app-release-unsigned.apk stays
# the reproducible artifact, which is what lets F-Droid verify a build against ours.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
out=${1:?usage: tools/sign-release.sh OUT.apk}
: "${RELEASE_KEYSTORE:?}" "${RELEASE_KEY_ALIAS:?}" "${RELEASE_KEYSTORE_PASSWORD:?}"
RELEASE_KEY_PASSWORD=${RELEASE_KEY_PASSWORD:-$RELEASE_KEYSTORE_PASSWORD}
export RELEASE_KEY_ALIAS RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_PASSWORD
unsigned=app/build/outputs/apk/release/app-release-unsigned.apk
[ -f "$unsigned" ] || { echo "sign-release: missing $unsigned; run tools/android-build.sh" >&2; exit 1; }
case $out in
    /*) echo "sign-release: OUT must be inside the workspace" >&2; exit 1 ;;
esac
podman_run --network=none \
  -v "$RELEASE_KEYSTORE:/keys/release.jks:ro" \
  -e RELEASE_KEY_ALIAS -e RELEASE_KEYSTORE_PASSWORD -e RELEASE_KEY_PASSWORD \
  -e UNSIGNED="$unsigned" -e OUT="$out" \
  "$ANDROID_IMAGE" sh -ec '
    mkdir -p "$(dirname "$OUT")"
    apksigner sign --ks /keys/release.jks --ks-key-alias "$RELEASE_KEY_ALIAS" \
        --ks-pass env:RELEASE_KEYSTORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD \
        --v4-signing-enabled false --out "$OUT" "$UNSIGNED"
    tools/verify-apk.sh "$OUT"
    ! aapt2 dump badging "$OUT" | grep -q "^application-debuggable" \
        || { echo "sign-release: $OUT is debuggable" >&2; exit 1; }
    apksigner verify --verbose --print-certs "$OUT" \
        | grep -E "^Verified using|^Signer #1 certificate (DN|SHA-256)"
  '
