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
}
