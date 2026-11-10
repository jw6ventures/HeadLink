// OsmAnd's router and .obf reader, vendored. See README.md in this directory for the pin,
// what was taken, the files HeadLink replaces, and why this is vendored source.
//
// A plain `java-library`, for the same reasons as vendor/brouter: an Android API creeping
// into this tree fails the build here rather than on a device, and the routing and reading
// code runs in tools/jni-host-test on a desktop JVM.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    named("main") {
        // HeadLink's own replacements for the few upstream files that reach outside
        // OsmAnd-java (README.md, "Replaced files"). Never the same path as an upstream file:
        // a class in both would be a duplicate-class build error.
        java.srcDir("src/replaced/java")
    }
}

dependencies {
    implementation(libs.trove4j)
    implementation(libs.jts.core)
    implementation(libs.openlocationcode)
    implementation(libs.gson)
    // Both are part of the Android framework, and lint rejects either in an APK
    // (DuplicatePlatformClasses). Compiled against here, supplied by the platform there,
    // and put on the classpath by tools/jni-host-test.sh on a desktop JVM.
    compileOnly(libs.json)
    compileOnly(libs.kxml2)
}

tasks.withType<JavaCompile>().configureEach {
    // Third-party source held unmodified: see vendor/brouter/build.gradle.kts.
    options.compilerArgs.add("-nowarn")
    options.isDeprecation = false
    options.encoding = "UTF-8"
}
