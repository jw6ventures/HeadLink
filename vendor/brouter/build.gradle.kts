// BRouter's routing engine, vendored (P6.3). See README.md in this directory for the
// pin, what was taken, and why this is vendored source rather than a dependency.
//
// Deliberately a plain `java-library` and not an Android library. BRouter's runtime
// modules import nothing but `btools.*` and `java.*`, and compiling them against the JVM
// rather than the Android SDK is what keeps that true: an Android API creeping into this
// tree on a future re-sync fails the build here instead of being discovered on a device.
// It also means routing logic can be exercised from tools/jni-host-test without an
// emulator, the same reason Dashboard and MapControl are pure JVM.
plugins {
    `java-library`
}

java {
    // Upstream compiles at release 11; the app's own floor is 17 and AGP requires a
    // library's class files not to exceed the consuming application's target.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    // Third-party source held unmodified: its warnings are not ours to fix, and treating
    // them as errors would make a re-sync a source-editing exercise. Everything the
    // project writes is still built with warnings as errors.
    options.compilerArgs.add("-nowarn")
    options.isDeprecation = false
}
