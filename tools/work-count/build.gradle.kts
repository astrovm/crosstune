// A Java agent that counts the work one thread does: each call, each time a loop goes round, and each element an array
// copy moves, in the app, Kotlin and the JDK, its patterns too. The tests that judge how reading grows (Growth.kt) count
// with it, as a count is the same on every machine, where a time is not. It runs on the JVM that runs the tests.
plugins {
    java
}

dependencies {
    // ASM writes the steps into the classes, as the pitest-changed-lines plugin reads them. The agent loads it apart
    // from the tests' classes, which may bring another ASM.
    implementation("org.ow2.asm:asm-tree:9.11.1")
}

// As the other projects: a build needs JDK 17 or newer (CONTRIBUTING.md).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

val work = "com/astrovm/crosstune/workcount/Work.class"

tasks.jar {
    // Work is in a jar of its own, which the boot class loader holds: the JDK's classes count in it too.
    exclude(work)
    manifest {
        attributes(
            "Premain-Class" to "com.astrovm.crosstune.workcount.Agent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}

val workJar = tasks.register<Jar>("workJar") {
    archiveClassifier.set("work")
    from(sourceSets.main.map { it.output }) { include(work) }
}

// The jar with Work alone, for the app's growthTests task.
configurations.consumable("work") {
    outgoing.artifact(workJar)
}
