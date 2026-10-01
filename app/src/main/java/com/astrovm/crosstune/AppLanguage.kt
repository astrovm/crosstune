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
    val tags = listOf("en", "es", "pt-BR", "de", "fr", "it", "nl", "pl", "ru", "tr", "id", "hi", "ja", "ko", "zh-CN")

    private const val KEY = "language"

    /** How speakers name these languages, where Java's own names read oddly. */
    private val names = mapOf("id" to "Bahasa Indonesia", "zh-CN" to "简体中文")

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
                tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
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
