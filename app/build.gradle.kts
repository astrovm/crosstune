plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlinx.kover")
}

fun Project.gitOutput(vararg args: String): String? {
    return try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) output.ifEmpty { null } else null
    } catch (_: Exception) {
        null
    }
}

// The release workflow names its tag, since git describe may pick another tag on the same commit.
val latestTagRef = providers.gradleProperty("releaseTag").orNull ?: gitOutput("describe", "--tags", "--abbrev=0")
val latestTagName = latestTagRef?.removePrefix("v")
val commitsSinceTag = latestTagRef?.let { gitOutput("rev-list", "--count", "$it..HEAD")?.toIntOrNull() }

val derivedVersionName = when {
    latestTagName == null -> "0.0.0-dev"
    commitsSinceTag == null || commitsSinceTag == 0 -> latestTagName
    else -> "$latestTagName-dev.$commitsSinceTag"
}

// From the tag, not the commit count, so a hotfix built from an older branch still installs as an update:
// vX.Y.Z is X*1000000 + Y*10000 + Z*100, and builds after the tag add up to 99.
val derivedVersionCode = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(latestTagName.orEmpty())
    ?.destructured
    ?.let { (major, minor, patch) ->
        major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + (commitsSinceTag ?: 0).coerceAtMost(99)
    }
    ?: 1

android {
    namespace = "com.astrovm.crosstune"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.astrovm.crosstune"
        minSdk = 26
        targetSdk = 37
        versionCode = derivedVersionCode
        versionName = derivedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Lists the languages under res/values-* in the app's locale config, so each shows up in
        // Android 13+'s per-app language setting without another file to keep in sync.
        generateLocaleConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kover {
    reports {
        variant("debug") {
            verify {
                rule {
                    minBound(100)
                }
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-coroutines:5.5.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Everything CI verifies, so `./gradlew :app:ci` locally runs exactly what the CI job does.
tasks.register("ci") {
    group = "verification"
    description = "Unit tests with the 100% line coverage gate, lint, and the debug and minified release APKs."
    dependsOn(
        "testDebugUnitTest",
        "koverLogDebug",
        "koverXmlReportDebug",
        "koverVerifyDebug",
        "lintDebug",
        "assembleDebug",
        "assembleRelease"
    )
}

// Kover doesn't hook its gate into `check`, so without this a local check passes below 100%.
tasks.named("check") {
    dependsOn("koverVerifyDebug")
}

tasks.withType<Test>().configureEach {
    // Robolectric loads a full Android runtime per SDK level the tests use.
    maxHeapSize = "3g"
    // Robolectric's Android 16 runtime reaches into JDK internals.
    jvmArgs(
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
