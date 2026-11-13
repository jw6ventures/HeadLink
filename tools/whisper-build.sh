#!/bin/sh
# Builds libwhisper_jni.so, the on-device speech recognizer (spec §2.1 as amended
# 11 November 2026): whisper.cpp and its ggml CPU backend, compiled from the pinned
# source with app/src/main/cpp/whisper_jni.cpp, as one shared library with libc++ linked
# in statically. Runs inside the Android image with the crate fetched by
# tools/fetch-deps.sh mounted at /cache/sources. Offline, stamped, and reproducible: no
# date, path or commit is compiled in, and the object order is fixed.
#
#   tools/whisper-build.sh aarch64-linux-android   -> target/whisper/aarch64-linux-android/
#   tools/whisper-build.sh x86_64-linux-gnu        -> target/whisper/x86_64-linux-gnu/ (host tests)
#
# The source is whisper.cpp 1.8.3 (ggml 0.9.5) as published inside the whisper-rs-sys
# 0.15.0 crate on crates.io, whose archive is immutable and checksummed below; only its
# whisper.cpp/ directory is used, unmodified. See docs/audio-and-speech.md, "Speech
# recognition", for why the crate and not a GitHub archive.
set -eu
WHISPER_VERSION=1.8.3
GGML_VERSION=0.9.5
CRATE=whisper-rs-sys-0.15.0
CRATE_SHA256=6986c0fe081241d391f09b9a071fbcbb59720c3563628c3c829057cf69f2a56f

target=${1:?usage: tools/whisper-build.sh aarch64-linux-android|x86_64-linux-gnu}
crate=/cache/sources/$CRATE.crate
root=$PWD
out=$root/target/whisper/$target
build=$root/target/whisper/build-$target

case $target in
aarch64-linux-android)
    # Armv8.2-A with half-precision and dot-product instructions: every arm64 core since
    # Cortex-A55/A75. WhisperNative checks /proc/cpuinfo for asimdhp and asimddp before
    # loading the library, so an older core gets a message instead of SIGILL.
    cc=aarch64-linux-android33-clang cxx=aarch64-linux-android33-clang++
    arch="-march=armv8.2-a+fp16+dotprod"
    arch_dir=arm
    # 16 KiB pages, as libaap_jni.so; liblog is not needed.
    link="-static-libstdc++ -Wl,-z,max-page-size=16384"
    ;;
x86_64-linux-gnu)
    # The JVM host test only; never packaged. Baseline x86-64 so any runner can run it.
    cc=${CC:-cc} cxx=${CXX:-c++}
    arch=
    arch_dir=x86
    link="-static-libstdc++ -static-libgcc"
    ;;
*)
    echo "whisper-build: unsupported target $target" >&2
    exit 1
    ;;
esac

defines="-DNDEBUG -D_GNU_SOURCE -D_XOPEN_SOURCE=600 -DGGML_USE_CPU -DGGML_USE_LLAMAFILE \
-DGGML_USE_CPU_REPACK -DGGML_SCHED_MAX_COPIES=4 -DGGML_VERSION=\"$GGML_VERSION\" \
-DGGML_COMMIT=\"unknown\" -DWHISPER_VERSION=\"$WHISPER_VERSION\""
# -ffile-prefix-map keeps the build directory out of assert strings and debug info.
common="-O3 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections -pthread \
-ffile-prefix-map=$build/=whisper.cpp/ -ffile-prefix-map=$root/=headlink/ $arch"
includes="-I$build/include -I$build/ggml/include -I$build/ggml/src -I$build/ggml/src/ggml-cpu"
cflags="-std=c11 $common $defines $includes"
cxxflags="-std=c++17 $common $defines $includes"

c_sources="ggml/src/ggml.c ggml/src/ggml-alloc.c ggml/src/ggml-quants.c \
ggml/src/ggml-cpu/ggml-cpu.c ggml/src/ggml-cpu/quants.c ggml/src/ggml-cpu/arch/$arch_dir/quants.c"
cxx_sources="ggml/src/ggml.cpp ggml/src/ggml-backend.cpp ggml/src/ggml-backend-reg.cpp \
ggml/src/ggml-opt.cpp ggml/src/ggml-threading.cpp ggml/src/gguf.cpp \
ggml/src/ggml-cpu/ggml-cpu.cpp ggml/src/ggml-cpu/repack.cpp ggml/src/ggml-cpu/hbm.cpp \
ggml/src/ggml-cpu/traits.cpp ggml/src/ggml-cpu/binary-ops.cpp ggml/src/ggml-cpu/unary-ops.cpp \
ggml/src/ggml-cpu/vec.cpp ggml/src/ggml-cpu/ops.cpp ggml/src/ggml-cpu/llamafile/sgemm.cpp \
ggml/src/ggml-cpu/arch/$arch_dir/repack.cpp src/whisper.cpp"
jni=app/src/main/cpp/whisper_jni.cpp

stamp="crate=$CRATE sha256=$CRATE_SHA256 target=$target cflags=$cflags cxxflags=$cxxflags link=$link \
jni=$(sha256sum "$jni" | cut -d' ' -f1) script=$(sha256sum "$0" | cut -d' ' -f1) \
cc=$($cc --version | head -n 1)"
if [ -f "$out/.stamp" ] && [ "$(cat "$out/.stamp")" = "$stamp" ]; then
    exit 0
fi
[ -f "$crate" ] || { echo "whisper-build: $crate missing; run tools/fetch-deps.sh" >&2; exit 1; }
echo "$CRATE_SHA256  $crate" | sha256sum -c --quiet -
rm -rf "$out" "$build"
mkdir -p "$out" "$build/obj"
tar -xzf "$crate" -C "$build" --strip-components=2 "$CRATE/whisper.cpp"
# JNI headers: the JDK's, which every Android image and host test has.
jni_includes="-I${JAVA_HOME:?JAVA_HOME unset}/include -I$JAVA_HOME/include/linux"

objects=
compile() { # compiler flags source
    object=$build/obj/$(printf '%s' "$3" | tr '/' '_').o
    # shellcheck disable=SC2086 # flags are word lists.
    $1 $2 -c "$3" -o "$object" || exit 1
    objects="$objects $object"
}
cd "$build"
for source in $c_sources; do compile "$cc" "$cflags" "$source"; done
for source in $cxx_sources; do compile "$cxx" "$cxxflags" "$source"; done
cd "$root"
compile "$cxx" "$cxxflags $jni_includes -Wall -Wextra -Werror" "$jni"

# Only the JNI entry points are exported; libc++, ggml and whisper.cpp stay internal (some
# ggml template specialisations would otherwise escape -fvisibility=hidden).
printf '{\n  global: Java_com_jw6ventures_headlink_WhisperNative_*;\n  local: *;\n};\n' > "$build/exports.map"
# shellcheck disable=SC2086
$cxx -shared -o "$out/libwhisper_jni.so" $objects -pthread $link \
    -Wl,--version-script="$build/exports.map" \
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--build-id=sha1 -Wl,-soname,libwhisper_jni.so \
    -Wl,-z,noexecstack -Wl,--no-undefined -ldl -lm
rm -rf "$build"
printf '%s\n' "$stamp" > "$out/.stamp"
echo "whisper-build: whisper.cpp $WHISPER_VERSION for $target in target/whisper/$target"
