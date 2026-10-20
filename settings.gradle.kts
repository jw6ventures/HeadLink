pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "HeadLink"
include(":app")
// BRouter's routing engine, vendored as source rather than resolved as a dependency:
// it publishes no Maven Central artifact at all. See vendor/brouter/README.md.
include(":vendor:brouter")
