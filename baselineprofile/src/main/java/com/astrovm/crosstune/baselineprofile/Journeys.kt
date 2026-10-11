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
private const val LOOKUP_START_MS = 2_000L
private const val STEP_MS = 1_000L

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
    // Which of the other steps show depends on the apps installed, and the ones asking Android for
    // something are skipped, so it's on to the next step until the main screen shows.
    while (true) {
        waitFor(By.text(Pattern.compile("Next|Finish|Skip for now")).enabled(true)).click()
        device.waitForIdle()
        if (device.wait(Until.hasObject(By.text(LINK_FIELD_LABEL)), STEP_MS)) break
    }
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
        // The field is disabled while the link is looked up. A link already in Recent shows at once
        // and never disables it, so this only waits a moment.
        device.wait(Until.hasObject(By.clazz("android.widget.EditText").enabled(false)), LOOKUP_START_MS)
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

/** A Japanese song found by its name, so its words come with readings, romaji and chords. */
private const val JAPANESE_SONG = "Plastic Love Mariya Takeuchi"
private const val JAPANESE_TITLE = "Plastic Love"
private const val LEARN_BUTTON = "Readings and translation"

/**
 * What people do with a song's words: find a song by its name, open its words, turn on readings
 * over them, romaji and chords, scroll through them, and look a word up. Without a connection the
 * words don't come, which still covers the search and the song's screen.
 */
internal fun MacrobenchmarkScope.useLyrics() {
    val field = waitFor(By.clazz("android.widget.EditText").enabled(true))
    field.text = JAPANESE_SONG
    waitFor(By.text("Convert link").enabled(true)).click()
    waitFor(By.text("Songs"))
    waitFor(By.text(JAPANESE_TITLE)).click()
    waitFor(By.text("Lyrics")).click()
    // The button shows once the words have come, and only for words in another language.
    val learn = device.wait(Until.findObject(By.desc(LEARN_BUTTON)), RESOLVE_TIMEOUT_MS)
    if (learn != null) {
        learn.click()
        for (option in listOf("Readings over the words", "In Latin letters", "Chords")) {
            // The row is what's checked, so an option left on from a run before stays on.
            val row = waitFor(By.text(option)).parent
            if (row != null && row.isCheckable && !row.isChecked) row.click()
            device.waitForIdle()
        }
        device.pressBack()
        device.waitForIdle()
    }
    scrollDownAndUp()
    // A word with a reading, looked up, then put away.
    device.findObject(By.text(Pattern.compile(".*[\\u4E00-\\u9FFF].*")))?.let { word ->
        word.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
    waitFor(By.desc("Dismiss")).click()
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
