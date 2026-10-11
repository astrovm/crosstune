package com.astrovm.crosstune

import android.app.Application
import android.icu.util.ULocale
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which language a song's words are in, as Android tells it. */
@RunWith(RobolectricTestRunner::class)
class LyricsLanguageTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val asked = mutableListOf<String>()

    /** Android, telling [answer] for whatever it's asked. */
    private fun language(answer: () -> TextLanguage) = AndroidLyricsLanguage(app, Dispatchers.Unconfined) {
        object : TextClassifier {
            override fun detectLanguage(request: TextLanguage.Request): TextLanguage {
                asked += request.text.toString()
                return answer()
            }
        }
    }

    private fun told(vararg languages: Pair<String, Float>) =
        TextLanguage.Builder().apply { languages.forEach { (tag, score) -> putLocale(ULocale.forLanguageTag(tag), score) } }.build()

    @Test
    fun aSureAnswerIsTheSongsLanguageFromItsWordsOnly() = runBlocking {
        val language = language { told("en" to 0.95f, "es" to 0.05f) }
        assertEquals("en", language.of(listOf("Night sky", "♪", "", "Stars")))
        // Lines with no words to read are left out.
        assertEquals(listOf("Night sky\nStars"), asked)
    }

    @Test
    fun aLongSongIsToldFromItsStartOnly() = runBlocking {
        language { told("en" to 0.95f) }.of(List(500) { "Night sky" })
        assertEquals(1_000, asked.single().length)
    }

    @Test
    fun anUnsureAnswerIsNone() = runBlocking {
        assertNull(language { told("en" to 0.79f, "es" to 0.21f) }.of(listOf("Night sky")))
        assertEquals("en", language { told("en" to 0.8f) }.of(listOf("Night sky")))
    }

    @Test
    fun indonesianIsToldByItsOwnCode() = runBlocking {
        assertEquals("id", language { told("in" to 0.9f) }.of(listOf("Langit malam")))
        assertEquals("id", language { told("id" to 0.9f) }.of(listOf("Langit malam")))
    }

    @Test
    fun chineseIsToldBySimplifiedOrTraditionalCharacters() = runBlocking {
        val chinese = language { told("zh" to 0.95f) }
        assertEquals("zh-CN", chinese.of(listOf("我们说过这些话")))
        assertEquals("zh-TW", chinese.of(listOf("我們說過這些話")))
        // Nothing written its own way either way is taken as simplified, the more written.
        assertEquals("zh-CN", chinese.of(listOf("月亮")))
        // Told how it's written, that's how.
        assertEquals("zh-TW", language { told("zh-Hant" to 0.95f) }.of(listOf("我们")))
        assertEquals("zh-CN", language { told("zh-Hans" to 0.95f) }.of(listOf("我們")))
    }

    @Test
    fun withNoAnswerOrNoOneToAskItCantBeTold() = runBlocking {
        assertNull(language { told() }.of(listOf("Night sky")))
        assertNull(language { throw IllegalStateException("Android's classifier is gone") }.of(listOf("Night sky")))
        assertNull(AndroidLyricsLanguage(app, Dispatchers.Unconfined) { null }.of(listOf("Night sky")))
    }

    @Test
    fun wordsWithNoLettersArentAskedAbout() = runBlocking {
        assertNull(language { told("en" to 0.95f) }.of(listOf("♪", "123", "")))
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid10ItCantBeTold() = runBlocking {
        assertNull(language { told("en" to 0.95f) }.of(listOf("Night sky")))
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun androidsOwnIsAskedByDefault() = runBlocking {
        // Robolectric's Android has no classifier of its own to answer with, which is no answer.
        assertNull(AndroidLyricsLanguage(app, Dispatchers.Unconfined).of(listOf("Night sky")))
    }
}
