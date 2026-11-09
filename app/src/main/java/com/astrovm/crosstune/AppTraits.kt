package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * What practice has seen a music app do, so a card tries the apps that work first. [seeks]: once the song
 * plays, the app's session offers to move in it. [keeps]: it goes on playing with Crosstune in front. Null
 * for what hasn't been seen, or has been too long to believe.
 */
internal data class AppTraits(val seeks: Boolean? = null, val keeps: Boolean? = null) {
    /** Seen to do both, so a line can be played there. */
    val works: Boolean get() = seeks == true && keeps == true

    /** Seen not to do one of them. */
    val fails: Boolean get() = seeks == false || keeps == false
}

/**
 * What each music app, by package, was last seen to do (see [AppTraits]), on the phone only. A mark that says
 * the app can't is believed for [FORGET_AFTER_MS], and then the app is tried again, as it may have been upgraded
 * or paid for since. One that says it can is believed until the app is seen not to, which every use shows.
 */
internal class AppTraitsStore(private val preferences: SharedPreferences, private val now: () -> Long) {

    /** What [app] was seen to do; nothing for an app not seen, or whose "can't" is old. */
    fun of(app: String): AppTraits {
        val seen = load().optJSONObject(app) ?: return AppTraits()
        val old = now() - seen.optLong(AT) >= FORGET_AFTER_MS
        fun mark(key: String) = if (seen.has(key)) seen.optBoolean(key).takeIf { it || !old } else null
        return AppTraits(mark(SEEKS), mark(KEEPS))
    }

    /** Remembers that [app]'s session [offers] to move in the song once it plays, or doesn't. */
    fun seeks(app: String, offers: Boolean) = remember(app, SEEKS, offers)

    /** Remembers that [app] goes on playing with Crosstune in front, or doesn't. */
    fun keeps(app: String, goesOn: Boolean) = remember(app, KEEPS, goesOn)

    private fun remember(app: String, key: String, value: Boolean) {
        val all = load()
        // A mark made now is as new as the app's others, so a "can't" ages from the last time it was seen.
        val seen = all.optJSONObject(app) ?: JSONObject()
        all.put(app, seen.put(key, value).put(AT, now()))
        preferences.edit { putString(KEY, all.toString()) }
    }

    private fun load(): JSONObject = preferences.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    companion object {
        /** How long an app seen not to seek, or not to go on playing, is believed before it's tried again: a week. */
        const val FORGET_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        private const val KEY = "app_traits"
        private const val SEEKS = "seeks"
        private const val KEEPS = "keeps"
        private const val AT = "at"
    }
}
