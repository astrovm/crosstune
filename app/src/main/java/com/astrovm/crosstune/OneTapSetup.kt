package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.os.Build

/** A shell with adb's powers, which can do what otherwise only the user can in Android's settings. */
internal interface Shell {
    /** Whether it's root rather than Shizuku. */
    val root: Boolean get() = false

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
 * Setup in one tap, through the first of [shells] that's ready, from Android 12, whose commands
 * these are: other apps stop opening the links, Crosstune opens its own, lyrics hear the music and
 * follow music apps, and float over other apps.
 */
internal class OneTapSetup(private val context: Context, private val shells: List<Shell>) {
    private fun shell(): Shell? = shells.firstOrNull { it.ready() }.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }

    fun available(): Boolean = shell() != null

    fun root(): Boolean = shell()?.root == true

    /** Shizuku installed but not running, as after the phone restarts, with nothing else to do it. */
    fun shizukuStopped(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && shell() == null &&
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null

    fun watch(changed: () -> Unit): () -> Unit {
        val stops = shells.map { it.watch(changed) }
        return { stops.forEach { it() } }
    }

    /**
     * Stops [apps], by package, opening links; lets Crosstune open its [links]; and, when asked to,
     * lets it [follow] music apps, use the [microphone], and show [floating] lyrics, with their
     * notification. Each is tried, so one failing leaves the rest done; whether all worked.
     */
    suspend fun setUp(apps: List<String>, links: Boolean, follow: Boolean, microphone: Boolean = false, floating: Boolean = false): Boolean {
        val shell = shell() ?: return false
        if (!shell.allowed()) return false
        return commands(apps, links, follow, microphone, floating).map { shell.run(it) }.all { it }
    }

    private fun commands(apps: List<String>, links: Boolean, follow: Boolean, microphone: Boolean, floating: Boolean): List<List<String>> = buildList {
        val user = (android.os.Process.myUid() / PER_USER_RANGE).toString()
        val own = context.packageName
        // Android won't let Crosstune take links another app verified, so those apps go first.
        apps.forEach { add(listOf("pm", "set-app-links-allowed", "--user", user, "--package", it, "false")) }
        if (links) {
            // Set to open in the browser, Crosstune's links would still not reach it.
            add(listOf("pm", "set-app-links-allowed", "--user", user, "--package", own, "true"))
            add(listOf("pm", "set-app-links-user-selection", "--user", user, "--package", own, "true", "all"))
        }
        if (follow) {
            add(listOf("cmd", "notification", "allow_listener", ComponentName(context, NowPlayingListener::class.java).flattenToString(), user))
        }
        if (microphone) add(listOf("pm", "grant", "--user", user, own, android.Manifest.permission.RECORD_AUDIO))
        if (floating) {
            add(listOf("appops", "set", "--user", user, own, "SYSTEM_ALERT_WINDOW", "allow"))
            // Locked, floating lyrics unlock from their notification, which Android 13 asks about.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(listOf("pm", "grant", "--user", user, own, android.Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    companion object {
        /** How many app ids each of the phone's users has, so a uid tells whose it is. */
        private const val PER_USER_RANGE = 100_000

        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
