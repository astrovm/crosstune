package com.astrovm.crosstune

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The language a song's words are in, as [AppLanguage.untranslatedTags] has it, e.g. "en"; null when it can't be told. */
internal fun interface LyricsLanguage {
    suspend fun of(lines: List<String>): String?
}

/**
 * Told by Android itself, on the phone, from 10 on; before that it can't be. Only a sure answer
 * counts, so a song mixing languages, or with too few words to tell, isn't taken for one of them.
 */
internal class AndroidLyricsLanguage(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** What tells, Android's own unless a test stands in for it. */
    private val classifier: () -> TextClassifier? = { context.getSystemService(TextClassificationManager::class.java)?.textClassifier }
) : LyricsLanguage {
    override suspend fun of(lines: List<String>): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val text = lines.filter { line -> line.any(Char::isLetter) }.joinToString("\n").take(MAX_CHARS)
        if (text.isEmpty()) return null
        // Android doesn't allow asking from the main thread.
        return withContext(dispatcher) {
            try {
                val told = (classifier() ?: return@withContext null).detectLanguage(TextLanguage.Request.Builder(text).build())
                if (told.localeHypothesisCount == 0) return@withContext null
                val locale = told.getLocale(0)
                tagOf(locale.language, locale.script, text).takeIf { told.getConfidenceScore(locale) >= SURE }
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    private companion object {
        /** Enough of the words to tell, and no more for Android to read through. */
        const val MAX_CHARS = 1_000
        const val SURE = 0.8f
    }
}

/**
 * [language], written in [script] when told, as [AppLanguage.untranslatedTags] has it: by its own
 * code rather than Java's old one, e.g. "in" for Indonesian, and Chinese by how [text] is written.
 */
internal fun tagOf(language: String, script: String, text: String): String = when (language) {
    "in" -> "id"
    "zh" -> if (script == "Hant" || script.isEmpty() && traditional(text)) "zh-TW" else "zh-CN"
    else -> language
}

/**
 * Whether Chinese [text] is written in traditional characters, as in Taiwan and Hong Kong, rather
 * than simplified ones: by the common characters each writes its own way, e.g. 這 rather than 这.
 */
private fun traditional(text: String): Boolean =
    text.count { it in TRADITIONAL } > text.count { it in SIMPLIFIED }

/** Common characters written one way in simplified Chinese and another in traditional, each beside its other. */
private const val SIMPLIFIED = "们这说时国来对会过还发么问为门开关见长东车书学买卖听话语网电写飞爱经现让应头员边气马鸟龙梦泪恋"
private const val TRADITIONAL = "們這說時國來對會過還發麼問為門開關見長東車書學買賣聽話語網電寫飛愛經現讓應頭員邊氣馬鳥龍夢淚戀"
