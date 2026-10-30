import java.lang.management.ManagementFactory
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlinx.kover")
    id("androidx.baselineprofile")
    id("pl.droidsonroids.pitest")
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
        versionCode = 2170100
        versionName = "2.17.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MilkDrop visuals, drawn by projectM, built for phones and for emulators.
        ndk {
            // One per line: Renovate reads listOf("a", "b", "c") as a Maven library.
            abiFilters += "arm64-v8a"
            abiFilters += "armeabi-v7a"
            abiFilters += "x86_64"
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
        // "Crosstune Dev": what Android Studio runs, so Live Edit works, and what's tried on a phone.
        // Its own package, so it installs next to the released app.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
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
            // UniDic's dictionary is fetched, see UniDic.kt, rather than making the app 46 MB bigger.
            excludes += "com/atilika/kuromoji/unidic/*.bin"
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

    lint {
        // Lint runs in full on the debug build in CI, so a release build doesn't check again.
        checkReleaseBuilds = false
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
                classes("com.astrovm.crosstune.NativeMilkdrop*", "com.astrovm.crosstune.GlThread", "com.astrovm.crosstune.MilkdropView*", "com.astrovm.crosstune.MilkdropBehind*")
                // Shizuku runs on a phone, where it's checked; what it's told to run is tested.
                classes("com.astrovm.crosstune.ShizukuShell*")
                // Root runs su, which only a rooted phone has, and none at hand is; what it's told to run is tested.
                classes("com.astrovm.crosstune.RootShell*")
                // Waking another music app binds to it, which needs a phone, where it's checked.
                classes("com.astrovm.crosstune.MediaBrowserWaker*")
                // A music app's shuffle and repeat are told through its own session, which needs a phone, where it's checked.
                classes("com.astrovm.crosstune.SessionModes*")
                // Hearing words runs whisper.cpp's native library, which unit tests don't load; it's
                // checked on a phone. What it hears is parsed, and lined up, in tested code.
                classes("com.astrovm.crosstune.NativeWhisper*")
            }
        }
        variant("debug") {
            verify {
                rule {
                    minBound(100)
                }
                // Not every branch is worth a test, so this only keeps the share from falling.
                rule("Branches") {
                    minBound(85, CoverageUnit.BRANCH)
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
    // Songs up next dragged into another order, scrolling the list on as they near its edge.
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // The home screen widget, in the app's own Material You style.
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")

    // Reads Japanese lyrics' kanji, for the kana over them; its dictionary is in the jar, so it works offline.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // UniDic, which knows far more kanji. Its dictionary, 150 MB, is left out of the app and fetched
    // when it's first needed; tests read it from the jar.
    implementation("com.atilika.kuromoji:kuromoji-unidic:0.9.0")

    // Sets everything up in one tap for people who run Shizuku, which can do what otherwise only the user can.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // A music app's shuffle, which Android's own media sessions have no word for; most apps' sessions
    // are the support library's, which has.
    implementation("androidx.media:media:1.8.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:okhttp-coroutines:5.5.1")
    // Installs the baseline profile on first launch when the app store didn't, e.g. F-Droid or a GitHub APK.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.glance:glance-appwidget-testing:1.2.0")

    pitest(project(":pitest-changed-lines"))

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Everything CI verifies, so `./gradlew :app:ci` locally runs exactly what the CI job does.
tasks.register("ci") {
    group = "verification"
    description = "Unit tests with the 100% line and 85% branch coverage gates, lint, detekt, and the debug and minified release APKs."
    dependsOn(
        "testDebugUnitTest",
        ":pitest-changed-lines:test",
        "koverLogDebug",
        "koverXmlReportDebug",
        "koverVerifyDebug",
        "lintDebug",
        "detekt",
        "assembleDebug",
        "assembleRelease"
    )
}

// Kover doesn't hook its gate into `check`, so without this a local check passes below 100%.
tasks.named("check") {
    dependsOn("koverVerifyDebug", "detekt")
}

// detekt keeps new logic from tangling: a function with too many paths, a condition with too many
// parts, blocks nested too deep. It runs on its own classpath, apart from the Kotlin the app builds with.
val detektCli by configurations.creating
dependencies {
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}
tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Checks the app's code for tangled logic, past what config/detekt/baseline.xml already lists."
    val sources = layout.projectDirectory.dir("src/main/java")
    val config = rootProject.layout.projectDirectory.file("config/detekt/detekt.yml")
    val baseline = rootProject.layout.projectDirectory.file("config/detekt/baseline.xml")
    val report = layout.buildDirectory.file("reports/detekt/detekt.txt")
    inputs.dir(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(config, baseline).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report)
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    // The Kotlin compiler inside detekt reads memory the old way, which Java 24 and up warn about.
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    args(
        "--input", sources.asFile.path,
        "--config", config.asFile.path,
        "--baseline", baseline.asFile.path,
        "--report", "txt:" + report.get().asFile.path,
    )
}

// The store's screenshots and the site's clips, drawn on this computer frame by frame: one test
// class per language, side by side. scripts/shots/shots.py runs it, with the visuals drawn ahead.
val shots = tasks.register<Test>("shots") {
    group = "documentation"
    description = "Draws the store screenshots and the site's clips in every language. Run scripts/shots/shots.py instead."
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching("com.astrovm.crosstune.shots.*")
    systemProperty("shots.out", layout.buildDirectory.dir("shots").get().asFile.path)
    systemProperty("shots.visuals", providers.gradleProperty("shotsVisuals").getOrElse(rootProject.file("build/shots-visuals").path))
    outputs.upToDateWhen { false }
}

// The app's lookups on the songs people play most, against the real services, a report per song.
// scripts/songcheck/songcheck.py runs it, with the songs and their previews fetched ahead.
val songCheck = tasks.register<Test>("songCheck") {
    group = "verification"
    description = "Checks lyrics, timing, chords, readings and translations on the top songs. Run scripts/songcheck/songcheck.py instead."
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching(providers.gradleProperty("songcheckTest").getOrElse("com.astrovm.crosstune.songcheck.SongCheck"))
    systemProperty("songcheck.dir", providers.gradleProperty("songcheckDir").getOrElse(rootProject.file("build/songcheck").path))
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}

// Robolectric's Android runtimes, one for each SDK level the tests run on, fetched by Gradle and kept
// with its caches, rather than by Robolectric as the tests run: on CI, a test process once read one
// before it was all there, and so ran on Android's empty stubs instead. One configuration each, as
// Gradle would otherwise keep only the newest of one library's versions.
val robolectricRuntimes = files(
    listOf("9-robolectric-4913185-2-i7", "10-robolectric-5803371-i7", "11-robolectric-6757853-i7", "12.1-robolectric-8229987-i7", "13-robolectric-9030017-i7", "16-robolectric-13921718-i7").map {
        configurations.detachedConfiguration(dependencies.create("org.robolectric:android-all-instrumented:$it")).apply { isTransitive = false }
    }
)

// Where each runtime is, for Robolectric to read; one it isn't told of fails the tests, not fetched.
val robolectricDeps = tasks.register("robolectricDeps") {
    val runtimes = robolectricRuntimes
    val out = layout.buildDirectory.file("robolectric-deps.properties")
    inputs.files(runtimes)
    outputs.file(out)
    doLast {
        // Keyed by each one's coordinates, their colons escaped, as a properties file reads one as "=".
        out.get().asFile.writeText(runtimes.files.sortedBy { it.name }.joinToString("") {
            "org.robolectric\\:android-all-instrumented\\:${it.name.removePrefix("android-all-instrumented-").removeSuffix(".jar")}=${it.absolutePath}\n"
        })
    }
}

// Robolectric's Android 16 runtime reaches into JDK internals, its native graphics load a library,
// and DataStore's protobuf reads memory through sun.misc.Unsafe; the JDK warns of the last two otherwise.
val robolectricJvmArgs = listOf(
    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
)

tasks.withType<Test>().configureEach {
    if (name != "shots") filter.excludeTestsMatching("com.astrovm.crosstune.shots.*")
    if (name != "songCheck") filter.excludeTestsMatching("com.astrovm.crosstune.songcheck.*")
    dependsOn(robolectricDeps)
    systemProperty("robolectric-deps.properties", layout.buildDirectory.file("robolectric-deps.properties").get().asFile.path)
    // Robolectric loads a full Android runtime per SDK level the tests use.
    maxHeapSize = "3g"
    // Two test processes, each with that heap, where there's room for them, like CI's runner:
    // the tests take about half the time.
    val memory = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize
    maxParallelForks = if (Runtime.getRuntime().availableProcessors() >= 4 && memory >= (12L shl 30)) 2 else 1
    jvmArgs(robolectricJvmArgs)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

pitest {
    pitestVersion.set("1.30.1")
    targetClasses.set(providers.gradleProperty("mutateClasses").map { it.split(",") }.orElse(listOf("com.astrovm.crosstune.*")))
    providers.gradleProperty("mutateTests").orNull?.let { targetTests.set(it.split(",")) }
    providers.gradleProperty("mutateLines").orNull?.let { features.set(listOf("+CHANGED(file[$it])")) }
    // The store's screenshots aren't tests of what the app does, and take long to draw; the song check asks real services.
    excludedTestClasses.set(listOf("com.astrovm.crosstune.shots.*", "com.astrovm.crosstune.songcheck.*"))
    threads.set(2)
    // A fresh test process takes some seconds to start Robolectric, more than PIT allows a test by default.
    timeoutConstInMillis.set(30_000)
    outputFormats.set(listOf("XML", "HTML"))
    timestampedReports.set(false)
    verbose.set(providers.gradleProperty("mutateVerbose").map { it.toBoolean() }.orElse(false))
    // Each mutant's tests run on Robolectric, with the Android runtimes the test task is told of.
    // Each mutant's classes are loaded again, and their adapters and stubs fill the JIT's small code
    // heap for them long before the Java heap fills, which PIT reports as a MEMORY_ERROR. One code
    // cache, unsplit, gives them the room.
    jvmArgs.set(robolectricJvmArgs + listOf("-Xmx3g", "-XX:-SegmentedCodeCache", "-XX:ReservedCodeCacheSize=512m", "-Drobolectric-deps.properties=" + layout.buildDirectory.file("robolectric-deps.properties").get().asFile.path))
}

// After the plugin's own setup, which gives the tests only what they compile against.
afterEvaluate {
    tasks.withType<pl.droidsonroids.gradle.pitest.PitestTask>().configureEach {
        dependsOn(robolectricDeps)
        // What the unit tests run with, like the app's resources, which Robolectric reads. The app's own
        // classes come from the folders PIT mutates, not the jar the tests get, or PIT finds nothing to mutate.
        additionalClasspath.setFrom(
            mutableCodePaths,
            tasks.named<Test>("testDebugUnitTest").map { test -> test.classpath.filter { "runtime_app_classes_jar" !in it.path } },
        )
    }
}

// The plugin adds the old libraries Android once shipped, for tests run against android.jar. Ours run on
// Robolectric with the classpath above, so they're never used, and they set off security alerts.
configurations.matching { it.name == "pitestTestCompile" }.configureEach { withDependencies { clear() } }
