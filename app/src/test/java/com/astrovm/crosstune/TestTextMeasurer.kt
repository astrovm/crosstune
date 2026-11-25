package com.astrovm.crosstune

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import java.util.Locale

/** Measures text at 1 px per dp, left to right, in the test device's own language. */
internal fun testTextMeasurer() = TextMeasurer(
    createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
    LocaleList(Locale.getDefault().toLanguageTag()),
    Density(1f),
    LayoutDirection.Ltr
)
