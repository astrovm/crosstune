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

val latestTagRef = gitOutput("describe", "--tags", "--abbrev=0")
val latestTagName = latestTagRef?.removePrefix("v")
val commitCount = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
val commitsSinceTag = latestTagRef?.let { gitOutput("rev-list", "--count", "$it..HEAD")?.toIntOrNull() }

val derivedVersionName = when {
    latestTagName == null -> "0.0.0-dev"
    commitsSinceTag == null || commitsSinceTag == 0 -> latestTagName
    else -> "$latestTagName-dev.$commitsSinceTag"
}

android {
    namespace = "com.astrovm.crosstune"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.astrovm.crosstune"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount
        versionName = derivedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main") {
            res.directories.clear()
            res.directories.add("src/main/res_clean")
        }
    }
}

kover {
    reports {
        variant("debug") {
            verify {
                rule {
                    minBound(95)
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
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
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
