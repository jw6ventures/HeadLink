# Shared Podman settings, sourced by tools/*.sh from the repository root.
# Caches live outside the workspace so the (small) project volume holds only sources
# and build output. --userns=keep-id keeps host ownership; label=disable avoids
# relabeling the workspace. keep-id would otherwise make the workdir the user's home,
# letting JVM tools write dot-directories (such as AGP's .android) into the workspace.
RUST_IMAGE=localhost/openauto-rust:1.85.1
ANDROID_IMAGE=localhost/openauto-android:p0.2
CACHE_ROOT=${OPENAUTO_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/openauto-android}
mkdir -p "$CACHE_ROOT/cargo" "$CACHE_ROOT/gradle" "$CACHE_ROOT/android-user" "$CACHE_ROOT/sources"

podman_run() {
    # shellcheck disable=SC2016 # Podman expands the $ variables in the entry.
    podman run --rm --userns=keep-id --security-opt label=disable \
        --passwd-entry '$USERNAME:*:$UID:$GID:$NAME:/tmp:/bin/sh' \
        -v "$PWD:/workspace" -w /workspace \
        -v "$CACHE_ROOT/cargo:/cache/cargo" -e CARGO_HOME=/cache/cargo \
        -v "$CACHE_ROOT/sources:/cache/sources:ro" \
        "$@"
}
