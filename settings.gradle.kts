pluginManagement {
    repositories {
        google()
        // Central rate-limits shared CI runners; its Google mirror avoids repeated 429s on cold builds.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Use the same mirror for the large Robolectric runtimes and the app's dependencies.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "Crosstune"
include(":app")
include(":baselineprofile")
include(":pitest-changed-lines")
project(":pitest-changed-lines").projectDir = file("tools/pitest-changed-lines")
include(":work-count")
project(":work-count").projectDir = file("tools/work-count")
