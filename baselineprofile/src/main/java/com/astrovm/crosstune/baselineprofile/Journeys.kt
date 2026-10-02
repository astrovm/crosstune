package com.astrovm.crosstune.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val PACKAGE_NAME = "com.astrovm.crosstune"

// The emulator can be slow, and a missed screen is a broken run anyway, so waits are generous.
private const val SCREEN_TIMEOUT_MS = 30_000L
private const val RESOLVE_TIMEOUT_MS = 30_000L

// The device's language has to be English, since these find the app's screens by their text.
private const val GET_STARTED = "Get started"
private const val LINK_FIELD_LABEL = "Music link"

/** Real links, so a working connection also covers looking them up and the Recent list. */
private val LINKS = listOf(
    "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
    "https://www.deezer.com/track/3135556",
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
)

/**
 * Waits for the first screen and walks through first-run setup if it shows, so every run ends up
 * on the main screen. Setup only shows once, but each benchmark starts from a fresh install.
 */
internal fun MacrobenchmarkScope.finishSetupIfShown() {
    val firstScreen = Pattern.compile("$GET_STARTED|$LINK_FIELD_LABEL")
    check(device.wait(Until.hasObject(By.text(firstScreen)), SCREEN_TIMEOUT_MS)) { "Crosstune didn't show a screen" }
    val getStarted = device.findObject(By.text(GET_STARTED)) ?: return
    getStarted.click()
    // The first step needs a choice before Next is enabled; any destination will do.
    waitFor(By.text("YouTube Music")).click()
    // Which of the other steps show depends on the apps installed, so it's Next until Finish. Each
    // step fades in, so it waits for either rather than checking for Finish mid-fade.
    while (true) {
        val button = waitFor(By.text(Pattern.compile("Next|Finish")).enabled(true))
        val finish = button.text == "Finish"
        button.click()
        device.waitForIdle()
        if (finish) break
    }
    waitFor(By.text(LINK_FIELD_LABEL))
}

/**
 * What people do on the main screen: type links and look them up, scroll down to Recent, and
 * visit the settings. Without a connection the lookups fail, which still covers the error path.
 */
internal fun MacrobenchmarkScope.useMainScreen() {
    for (link in LINKS) {
        val field = waitFor(By.clazz("android.widget.EditText").enabled(true))
        field.text = link
        waitFor(By.text("Convert link").enabled(true)).click()
        // The field is disabled while the link is looked up, whatever the outcome.
        device.wait(Until.hasObject(By.clazz("android.widget.EditText").enabled(false)), SCREEN_TIMEOUT_MS)
        device.wait(Until.hasObject(By.clazz("android.widget.EditText").enabled(true)), RESOLVE_TIMEOUT_MS)
    }
    // Hides the keyboard, so the page has room to scroll. Back with no keyboard up would leave the app.
    if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
    scrollDownAndUp()

    waitFor(By.desc("Settings")).click()
    waitFor(By.text("Settings"))
    scrollDownAndUp()
    device.pressBack()
    waitFor(By.text(LINK_FIELD_LABEL))
}

private fun MacrobenchmarkScope.scrollDownAndUp() {
    fling(Direction.DOWN)
    fling(Direction.UP)
}

/** Compose can replace the scrolling page at any moment, so it's found again, and again if it goes stale. */
private fun MacrobenchmarkScope.fling(direction: Direction) {
    repeat(3) {
        val page = device.findObject(By.scrollable(true)) ?: return
        try {
            // Keeps the fling clear of the system gesture areas at the screen's edges.
            page.setGestureMarginPercentage(0.2f)
            page.fling(direction)
            device.waitForIdle()
            return
        } catch (_: StaleObjectException) {
            device.waitForIdle()
        }
    }
}

private fun MacrobenchmarkScope.waitFor(selector: BySelector): UiObject2 {
    // A busy emulator can show "System UI isn't responding" over the app; waiting it out is enough.
    repeat(SCREEN_TIMEOUT_MS.toInt() / 1_000) {
        device.findObject(By.text(NOT_RESPONDING))?.let { device.findObject(By.text("Wait"))?.click() }
        device.wait(Until.findObject(selector), 1_000L)?.let { return it }
    }
    error("Timed out waiting for $selector")
}

private val NOT_RESPONDING = Pattern.compile(".*isn't responding")
