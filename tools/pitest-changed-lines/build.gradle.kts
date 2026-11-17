// A PIT plugin that keeps only the mutants on the lines a branch changed: scripts/mutants.py
// lists them, so a run takes minutes rather than the hours the whole app would. It leaves out what
// Kotlin and Compose add to the app's code too, which no test of the app can tell apart.
plugins {
    `java-library`
}

// The PIT the app runs (gradle.properties).
val pitest = providers.gradleProperty("pitest.version").get()

dependencies {
    compileOnly("org.pitest:pitest-entry:$pitest")
    // PIT brings ASM, which it reads classes with.
    compileOnly("org.ow2.asm:asm-analysis:9.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.ow2.asm:asm-analysis:9.11.1")
    // The filter itself is tested as PIT runs it, on mutations of a class built here.
    testImplementation("org.pitest:pitest-entry:$pitest")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
