# Sourced inside the build images before Cargo runs: links openssl-sys statically
# against the pinned libraries from tools/openssl-build.sh instead of any system copy.
X86_64_UNKNOWN_LINUX_GNU_OPENSSL_DIR=$PWD/target/openssl/x86_64-unknown-linux-gnu
AARCH64_LINUX_ANDROID_OPENSSL_DIR=$PWD/target/openssl/aarch64-linux-android
X86_64_LINUX_ANDROID_OPENSSL_DIR=$PWD/target/openssl/x86_64-linux-android
OPENSSL_STATIC=1
export X86_64_UNKNOWN_LINUX_GNU_OPENSSL_DIR AARCH64_LINUX_ANDROID_OPENSSL_DIR X86_64_LINUX_ANDROID_OPENSSL_DIR \
    OPENSSL_STATIC
