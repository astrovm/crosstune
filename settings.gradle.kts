pluginManagement {
    repositories {
        google()
        // CI tests may opt into the mirror to avoid Central's 429s. An outage fails the build;
        // rerun without the variable or later. Release and local builds use Central directly.
        if (System.getenv("CROSSTUNE_MAVEN_MIRROR") == "true") {
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
        }
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
        // The mirror is for CI tests only; an outage fails the build, so rerun without it or later.
        if (System.getenv("CROSSTUNE_MAVEN_MIRROR") == "true") {
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
        }
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
