package com.astrovm.crosstune

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The language a song's words are in, by its code, e.g. "en"; null when it can't be told. */
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
                code(locale.language).takeIf { told.getConfidenceScore(locale) >= SURE }
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

/** [language] as [AppLanguage.untranslatedTags] has it, rather than Java's old code for it, e.g. "in" for Indonesian. */
internal fun code(language: String): String = when (language) {
    "in" -> "id"
    else -> language
}
