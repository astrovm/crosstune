plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlinx.kover")
    id("androidx.baselineprofile")
}

// projectM comes as a submodule. A checkout without it, like GitHub's own code scanning, still
// builds, just with no visuals: they need the native library, and the app runs fine without it.
val projectM = rootProject.file("third_party/projectm/CMakeLists.txt").exists()
if (!projectM) logger.warn("third_party/projectm is missing, so this build has no MilkDrop visuals. Run: git submodule update --init --recursive")

android {
    namespace = "com.astrovm.crosstune"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.astrovm.crosstune"
        minSdk = 26
        targetSdk = 37
        // For X.Y.Z use X*1000000 + Y*10000 + Z*100. Update both values for each release.
        versionCode = 2060000
        versionName = "2.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MilkDrop visuals, drawn by projectM, built for phones and for emulators.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        if (projectM) {
            externalNativeBuild {
                cmake {
                    // One library, with nothing else to ship alongside it.
                    arguments += "-DANDROID_STL=c++_static"
                }
            }
        }
    }

    // Pinned, so every build, F-Droid's too, makes the same native library.
    ndkVersion = "30.0.16248370"
    if (projectM) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "4.1.2"
            }
        }
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
        // "Crosstune Dev": optimized like the release, so what's tried on a phone runs as fast as
        // what ships, but signed with the debug key, so it installs next to the released app.
        create("dev") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Kuromoji's core and dictionary jars each carry the same notes; one copy is enough.
            pickFirsts += setOf("META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }

    bundle {
        // The in-app language picker needs every language on the phone, not only the phone's own.
        language {
            enableSplit = false
        }
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

baselineProfile {
    // One profile for every build, kept in src/main so F-Droid's build of a tag has the same one.
    mergeIntoMain = true
    // Builds only use the committed profile. Generating one needs a device, which CI doesn't have.
    automaticGenerationDuringBuild = false
}

kover {
    reports {
        filters {
            excludes {
                // The MilkDrop visuals' native library and GL thread need a GPU, which unit tests
                // don't have; they're checked on a phone. What they're told to do is tested.
                classes("com.astrovm.crosstune.NativeMilkdrop*", "com.astrovm.crosstune.GlThread", "com.astrovm.crosstune.MilkdropView*")
            }
        }
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

    // The home screen widget, in the app's own Material You style.
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")

    // Reads Japanese lyrics' kanji, for the kana over them; its dictionary is in the jar, so it works offline.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-coroutines:5.5.0")
    // Installs the baseline profile on first launch when the app store didn't, e.g. F-Droid or a GitHub APK.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.glance:glance-appwidget-testing:1.2.0")

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
