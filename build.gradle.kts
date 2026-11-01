buildscript {
    dependencies {
        constraints {
            // AGP's build-time tooling still pulls vulnerable versions of these; force patched releases.
            classpath("org.freemarker:freemarker:2.3.35")
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcutil-jdk18on:1.86")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.commons:commons-lang3:3.21.0")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
            classpath("com.google.guava:guava:33.7.2-jre")
        }
    }
}

// Tests and lint pull a vulnerable Guava too, which never ships in the app; use the patched one there as well.
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.google.guava" && requested.name == "guava") useVersion("33.7.2-jre")
        }
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.android.test") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("androidx.baselineprofile") version "1.5.0" apply false
    id("pl.droidsonroids.pitest") version "0.3.0" apply false
}
