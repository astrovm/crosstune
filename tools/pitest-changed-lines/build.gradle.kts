// A PIT plugin that keeps only the mutants on the lines a branch changed: scripts/mutants.py
// lists them, so a run takes minutes rather than the hours the whole app would.
plugins {
    `java-library`
}

dependencies {
    compileOnly("org.pitest:pitest-entry:1.30.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
