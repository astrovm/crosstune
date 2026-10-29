// A PIT plugin that keeps only the mutants on the lines a branch changed: scripts/mutants.py
// lists them, so a run takes minutes rather than the hours the whole app would. It leaves out what
// Kotlin and Compose add to the app's code too, which no test of the app can tell apart.
plugins {
    `java-library`
}

dependencies {
    compileOnly("org.pitest:pitest-entry:1.30.1")
    // PIT brings ASM, which it reads classes with.
    compileOnly("org.ow2.asm:asm-analysis:9.9")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.ow2.asm:asm-analysis:9.9")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
