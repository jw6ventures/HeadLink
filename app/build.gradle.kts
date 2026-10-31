plugins {
    alias(libs.plugins.android.application)
}

// Staged by tools/android-build.sh from the Rust aarch64-linux-android build.
// Gradle never invokes Cargo, and refuses to package an APK without the library.
val rustJniLibs: File = rootProject.file("target/android-jniLibs")
val requiredNativeLibrary: File = rustJniLibs.resolve("arm64-v8a/libaap_jni.so")

android {
    namespace = "com.jw6ventures.headlink"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.jw6ventures.headlink"
        minSdk = 33
        targetSdk = 36
        versionCode = 23
        versionName = "0.7.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories += rustJniLibs.path
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Inert while minification is off; see the file for why each rule exists.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Store uncompressed and page-aligned; load directly from the APK.
            useLegacyPackaging = false
        }
    }

    // No Google-encrypted dependency metadata block in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        warningsAsErrors = true
        // SDK levels are the spec §5.1 baseline; arm64-v8a is the only target ABI. The two
        // "newer version available" checks ask the network, so an online build (CI) would
        // fail whenever Google ships a release; versions move by a deliberate pin change.
        disable += setOf(
            "OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "ChromeOsAbiSupport",
        )
    }
}

// P5.5's owned map renderer. VTM core has no dependencies of its own; vtm-android adds
// only androidsvg, and vtm-http — the one networked module — is deliberately not here, so
// the map read path stays offline (APP10). The natives jar carries a single 45 KB
// tessellator for the one target ABI.
//
// These are plain JARs, not AARs: their shaders and theme symbols land in the APK as
// classpath resources rather than in the AssetManager, which is why MapAssets reads them
// through the classloader instead of using VTM's own AndroidAssets.
dependencies {
    implementation(libs.vtm)
    implementation(libs.vtm.android)
    implementation(libs.vtm.themes)
    runtimeOnly(variantOf(libs.vtm.android) { classifier("natives-arm64-v8a") })
    // P6.3's offline routing engine. Vendored source, not a resolved artifact: BRouter
    // publishes nothing to Maven Central, so there is no checksummed coordinate for
    // verification-metadata.xml to pin. Pure Java, no native library, no new ABI.
    implementation(project(":vendor:brouter"))
}

val checkRustJniLibs = tasks.register("checkRustJniLibs") {
    val library = requiredNativeLibrary
    doLast {
        check(library.isFile) { "Missing $library; build with tools/android-build.sh" }
    }
}
tasks.named("preBuild") {
    dependsOn(checkRustJniLibs)
}
