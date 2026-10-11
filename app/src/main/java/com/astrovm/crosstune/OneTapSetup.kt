package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.os.Build

/** A shell with adb's powers, which can do what otherwise only the user can in Android's settings. */
internal interface Shell {
    /** Whether it can run commands now. */
    fun ready(): Boolean

    /** Tells [changed] whenever it starts or stops, until the returned stop is called. */
    fun watch(changed: () -> Unit): () -> Unit

    /** Asks the user to let Crosstune use it, unless they have; whether they do. */
    suspend fun allowed(): Boolean

    /** Runs [command]; whether it worked. */
    suspend fun run(command: List<String>): Boolean
}

/**
 * Setup in one tap, through [shell], from Android 12, whose commands these are: other apps stop
 * opening the links, Crosstune opens its own, and lyrics follow music apps.
 */
internal class OneTapSetup(private val context: Context, private val shell: Shell) {
    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && shell.ready()

    fun watch(changed: () -> Unit): () -> Unit = shell.watch(changed)

    /**
     * Stops [apps], by package, opening links; lets Crosstune open its [links]; and, when asked to,
     * lets it [follow] music apps. Each is tried, so one failing leaves the rest done; whether all worked.
     */
    suspend fun setUp(apps: List<String>, links: Boolean, follow: Boolean): Boolean {
        if (!shell.allowed()) return false
        return commands(apps, links, follow).map { shell.run(it) }.all { it }
    }

    private fun commands(apps: List<String>, links: Boolean, follow: Boolean): List<List<String>> = buildList {
        val user = (android.os.Process.myUid() / PER_USER_RANGE).toString()
        // Android won't let Crosstune take links another app verified, so those apps go first.
        apps.forEach { add(listOf("pm", "set-app-links-allowed", "--user", user, "--package", it, "false")) }
        if (links) {
            // Set to open in the browser, Crosstune's links would still not reach it.
            add(listOf("pm", "set-app-links-allowed", "--user", user, "--package", context.packageName, "true"))
            add(listOf("pm", "set-app-links-user-selection", "--user", user, "--package", context.packageName, "true", "all"))
        }
        if (follow) {
            add(listOf("cmd", "notification", "allow_listener", ComponentName(context, NowPlayingListener::class.java).flattenToString(), user))
        }
    }

    private companion object {
        /** How many app ids each of the phone's users has, so a uid tells whose it is. */
        const val PER_USER_RANGE = 100_000
    }
}
