package com.astrovm.crosstune

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppLanguageTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val olderAndroid = Build.VERSION_CODES.S_V2

    @After
    fun forgetLanguage() {
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun olderAndroidSavesTheLanguageAndAppliesItToEachScreen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        AppLanguage.set(activity, "ja", olderAndroid)
        assertEquals("ja", AppLanguage.current(app, olderAndroid))
        assertEquals("ja", AppLanguage.wrap(app, olderAndroid).resources.configuration.locales[0].language)

        AppLanguage.set(activity, null, olderAndroid)
        assertNull(AppLanguage.current(app, olderAndroid))
        assertSame(app, AppLanguage.wrap(app, olderAndroid))
    }

    @Test
    fun newerAndroidAppliesTheLanguageItself() {
        assertSame(app, AppLanguage.wrap(app))
    }

    @Test
    fun namesEachLanguageInThatLanguage() {
        assertEquals("Español", AppLanguage.displayName("es"))
        assertEquals("日本語", AppLanguage.displayName("ja"))
        assertEquals("Português (Brasil)", AppLanguage.displayName("pt-BR"))
        assertEquals("简体中文", AppLanguage.displayName("zh-CN"))
    }
}
