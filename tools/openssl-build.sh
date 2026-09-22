#!/bin/sh
# Builds the pinned OpenSSL LTS release as static, position-independent libraries for
# one Rust target and installs them into target/openssl/<target>, where openssl-sys
# finds them through <TARGET>_OPENSSL_DIR (see tools/openssl-env.sh). Runs inside the
# Rust or Android image with the source tarball fetched by tools/fetch-deps.sh mounted
# at /cache/sources. Offline, idempotent (stamped), and reproducible: the build date
# comes from SOURCE_DATE_EPOCH and no source file names are compiled in.
#
#   tools/openssl-build.sh x86_64-unknown-linux-gnu | aarch64-linux-android | x86_64-linux-android
set -eu
OPENSSL_VERSION=3.5.8
OPENSSL_SHA256=a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2
# Upstream release date (2026-08-25T00:00:00Z), embedded as the "built on" string.
SOURCE_DATE_EPOCH=1787616000
export SOURCE_DATE_EPOCH

target=${1:?usage: tools/openssl-build.sh RUST_TARGET}
tarball=/cache/sources/openssl-$OPENSSL_VERSION.tar.gz
prefix=$PWD/target/openssl/$target
build=$PWD/target/openssl/build-$target

case $target in
x86_64-unknown-linux-gnu)
    os=linux-x86_64 cc=cc ar=ar ranlib=ranlib platform=
    ;;
aarch64-linux-android)
    # As openssl-src does: the generic Linux target with the NDK API-33 clang driver.
    # no-stdio avoids stdio-dependent code that fails to build against bionic.
    os=linux-aarch64 cc=aarch64-linux-android33-clang ar=llvm-ar ranlib=llvm-ranlib platform=no-stdio
    ;;
x86_64-linux-android)
    # Emulator smoke tests only (tools/emulator-smoke.sh); never packaged in the APKs.
    os=linux-x86_64 cc=x86_64-linux-android33-clang ar=llvm-ar ranlib=llvm-ranlib platform=no-stdio
    ;;
*)
    echo "openssl-build: unsupported target $target" >&2
    exit 1
    ;;
esac

# TLS 1.2 server use only. Everything removed here is either unused by aap-tls or
# detected by openssl-sys, so the Rust bindings stay consistent with the library.
# no-autoload-config: never read an openssl.cnf from the build or device filesystem.
options="no-shared no-module no-dso no-engine no-tests no-apps no-docs \
no-autoload-config no-filenames no-legacy no-ssl3 no-weak-ssl-ciphers no-md2 no-rc5 \
no-idea no-seed no-camellia no-comp no-zlib no-ec2m no-psk no-srp no-srtp no-quic \
no-cms no-ocsp no-nextprotoneg $platform"
flags="-fPIC -ffunction-sections -fdata-sections"
stamp="openssl=$OPENSSL_VERSION sha256=$OPENSSL_SHA256 target=$target os=$os options=$options flags=$flags \
epoch=$SOURCE_DATE_EPOCH cc=$($cc --version | head -n 1)"

if [ -f "$prefix/.stamp" ] && [ "$(cat "$prefix/.stamp")" = "$stamp" ]; then
    exit 0
fi
[ -f "$tarball" ] || { echo "openssl-build: $tarball missing; run tools/fetch-deps.sh" >&2; exit 1; }
echo "$OPENSSL_SHA256  $tarball" | sha256sum -c --quiet -
rm -rf "$prefix" "$build"
mkdir -p "$build"
tar -xzf "$tarball" -C "$build" --strip-components=1
cd "$build"
# shellcheck disable=SC2086 # options and flags are word lists.
CC=$cc AR=$ar RANLIB=$ranlib perl ./Configure "$os" --prefix="$prefix" \
    --openssldir=/nonexistent/openautoandroid-openssl --libdir=lib $options $flags > configure.log
make -j"$(nproc)" build_libs > build.log 2>&1 || { tail -n 40 build.log >&2; exit 1; }
make install_dev > install.log
cd - > /dev/null
rm -rf "$build" "$prefix/lib/pkgconfig" "$prefix/lib/cmake"
printf '%s\n' "$stamp" > "$prefix/.stamp"
echo "openssl-build: OpenSSL $OPENSSL_VERSION for $target in target/openssl/$target"
