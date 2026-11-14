// A Java agent that counts the work one thread does: each call, each time a loop goes round, and each element an array
// copy moves, in the app, Kotlin and the JDK, its patterns too. The tests that judge how reading grows (Growth.kt) count
// with it, as a count is the same on every machine, where a time is not. It runs on the JVM that runs the tests.
plugins {
    java
}

tasks.withType<JavaCompile>().configureEach {
    // java.lang.classfile, which reads and writes the classes, is in the JDK from 24 on.
    options.release.set(25)
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "com.astrovm.crosstune.workcount.Agent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}
