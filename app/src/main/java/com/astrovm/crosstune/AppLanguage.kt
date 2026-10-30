package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * The language Crosstune shows, picked in its settings. Android 13 and newer keep it as the app's
 * system language setting, so both places agree. Older versions have no such setting, so Crosstune
 * saves it and applies it to each screen itself.
 */
internal object AppLanguage {
    /** The languages under res/values-*, in the order the picker lists them. */
    val tags = listOf("en", "es", "pt-BR", "de", "fr", "it", "nl", "pl", "ru", "tr", "id", "hi", "ja", "ko", "zh-CN", "zh-TW")

    /**
     * The languages lyrics are translated into: the app's, by where they're spoken where that
     * changes the words, e.g. Argentina's Spanish, with vos. The first of each is its own default.
     */
    val translationTags = listOf(
        "en-US", "en-GB", "es-AR", "es-ES", "es-MX", "pt-BR", "pt-PT", "de", "fr-FR", "fr-CA", "it", "nl", "pl", "ru", "tr",
        "id", "hi", "ja", "ko", "zh-CN", "zh-TW"
    )

    /** The languages whose songs can be left untranslated: the app's, Chinese as written in each way. */
    val untranslatedTags = listOf("en", "es", "pt", "de", "fr", "it", "nl", "pl", "ru", "tr", "id", "hi", "ja", "ko", "zh-CN", "zh-TW")

    /** [tag], a language lyrics are translated into, as [untranslatedTags] has it: "es" for "es-AR", but Chinese by how it's written. */
    fun untranslatedTag(tag: String): String = if (tag in untranslatedTags) tag else Locale.forLanguageTag(tag).language

    /** What lyrics are translated into for [tag]: itself, or its language's first, e.g. "es-AR" for one saved as "es"; null for none. */
    fun translationTag(tag: String): String? {
        if (tag in translationTags) return tag
        val language = Locale.forLanguageTag(tag).language
        return translationTags.firstOrNull { Locale.forLanguageTag(it).language == language }
    }

    private const val KEY = "language"

    /** Each language's flag, from where its speakers, or this app's words in it, come. */
    private val flags = mapOf(
        "en" to "🇺🇸", "es" to "🇦🇷", "pt-BR" to "🇧🇷", "de" to "🇩🇪", "fr" to "🇫🇷", "it" to "🇮🇹", "nl" to "🇳🇱", "pl" to "🇵🇱",
        "ru" to "🇷🇺", "tr" to "🇹🇷", "id" to "🇮🇩", "hi" to "🇮🇳", "ja" to "🇯🇵", "ko" to "🇰🇷", "zh-CN" to "🇨🇳",
        "en-US" to "🇺🇸", "en-GB" to "🇬🇧", "es-AR" to "🇦🇷", "es-ES" to "🇪🇸", "es-MX" to "🇲🇽", "pt-PT" to "🇵🇹",
        "fr-FR" to "🇫🇷", "fr-CA" to "🇨🇦", "zh-TW" to "🇹🇼", "pt" to "🇧🇷"
    )

    /** The flag shown by the language [tag], or null for one without. */
    fun flag(tag: String): String? = flags[tag]

    /** How speakers name these languages, where Java's own names read oddly. */
    private val names = mapOf("id" to "Bahasa Indonesia", "zh-CN" to "简体中文", "zh-TW" to "繁體中文")

    /** The picked language tag, or null to follow the phone. [sdk] guards the Android 13 calls. */
    @SuppressLint("NewApi")
    fun current(context: Context, sdk: Int = Build.VERSION.SDK_INT): String? =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().ifEmpty { null }
        } else {
            preferences(context).getString(KEY, null)
        }

    @SuppressLint("NewApi")
    fun set(activity: Activity, tag: String?, sdk: Int = Build.VERSION.SDK_INT) {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            // Android recreates the screen in the new language.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            preferences(activity).edit { if (tag == null) remove(KEY) else putString(KEY, tag) }
            activity.recreate()
        }
    }

    /** Applies the saved language to a screen on versions where Android doesn't. */
    fun wrap(base: Context, sdk: Int = Build.VERSION.SDK_INT): Context {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = preferences(base).getString(KEY, null) ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(configuration)
    }

    /** A language's name in that language, e.g. "Español" or "日本語". */
    fun displayName(tag: String): String {
        names[tag]?.let { return it }
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
}
