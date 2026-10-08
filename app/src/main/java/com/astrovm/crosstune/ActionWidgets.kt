package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle

/**
 * One tap on the home screen for what Crosstune does most from there: a round button, and its name
 * below it where there's room.
 */
abstract class ActionWidget : GlanceAppWidget() {
    internal abstract val icon: Int
    internal abstract val label: Int
    internal abstract fun intent(context: Context): Intent

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LABELLED))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = CrosstuneWidget.widgetPalette(context)
        provideContent { CrosstuneWidget.WidgetTheme(palette) { ActionContent(icon, label, intent(context)) } }
    }

    internal companion object {
        val SMALL = DpSize(40.dp, 40.dp)
        val LABELLED = DpSize(80.dp, 90.dp)
    }
}

/** Names the song playing nearby, with the app picked in Settings, as the big widget's button does. */
class RecognizeWidget : ActionWidget() {
    override val icon = R.drawable.ic_recognize
    override val label = R.string.widget_recognize_label
    override fun intent(context: Context) = Intent(context, RecognizeSongActivity::class.java)
}

/** Names the song playing nearby and shows its words, in time with it, song after song. */
class LyricsWidget : ActionWidget() {
    override val icon = R.drawable.ic_lyrics
    override val label = R.string.widget_lyrics_label
    override fun intent(context: Context) = MainActivity.lyricsIntent(context)
}

class RecognizeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecognizeWidget()
}

class LyricsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LyricsWidget()
}

@Composable
internal fun ActionContent(icon: Int, label: Int, intent: Intent) {
    val context = LocalContext.current
    val name = context.getString(label)
    val labelled = LocalSize.current.height >= ActionWidget.LABELLED.height
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(actionStartActivity(intent))
            .semantics { contentDescription = name }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier.size(48.dp).cornerRadius(24.dp).background(GlanceTheme.colors.primaryContainer)
            ) {
                Image(
                    ImageProvider(icon),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                    modifier = GlanceModifier.size(26.dp)
                )
            }
            if (labelled) {
                Text(
                    name,
                    maxLines = 1,
                    modifier = GlanceModifier.padding(top = 6.dp),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                )
            }
        }
    }
}
