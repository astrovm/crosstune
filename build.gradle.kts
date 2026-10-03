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
        }
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.test") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("androidx.baselineprofile") version "1.5.0" apply false
}
