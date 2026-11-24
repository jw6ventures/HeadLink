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
// OsmAnd's router and .obf reader, vendored as source rather than resolved as a dependency:
// it publishes no checksummed Maven Central artifact. See vendor/osmand/README.md.
include(":vendor:osmand")
