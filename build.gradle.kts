buildscript {
    dependencies {
        // Explicit so a transitive change cannot silently move the Kotlin compiler.
        classpath(libs.kotlin.gradle.plugin)
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
}
