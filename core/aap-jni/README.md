Android native bridge packaged as `libaap_jni.so` (arm64-v8a, OpenSSL linked
statically). `JNI_OnLoad` registers `NativeBridge.nativeProbe`, `nativeStart`,
`nativeStop`, `nativeGetReport`, and `nativeEnsureIdentity` with explicit signatures.
`manager` is the JVM-independent owned-descriptor session registry (strict config,
identity loading, generation handles, one session at a time, join on stop) and
`ensure_identity` (load, create, or replace the source identity, then the TLS
self-test); `lib.rs` adapts them to JNI. Host tests: `tests/manager.rs` (including a
full discovery session with ../../tools/hu-sim); JVM contract test:
../../tools/jni-host-test.sh.
