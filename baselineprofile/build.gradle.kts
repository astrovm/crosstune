plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

// Generates the app's baseline profile and measures its startup on a device or emulator.
// Nothing here runs in CI, which has no device; the app only uses the profile committed to it.
android {
    namespace = "com.astrovm.crosstune.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Collecting a profile without root needs Android 9.
        minSdk = 28
        targetSdk = 37

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The app has a play and a foss build; the profile is made on the play one, and shared by both.
        missingDimensionStrategy("store", "play")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Uses whatever device or emulator is connected rather than a Gradle managed one.
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    constraints {
        // benchmark-macro 1.5.0 brings Wire 6.4.0, which has a decoder crash (Dependabot alert 56).
        // Drop this once a newer benchmark-macro brings 6.4.5 or later.
        implementation("com.squareup.wire:wire-runtime:7.1.2")
    }
}
