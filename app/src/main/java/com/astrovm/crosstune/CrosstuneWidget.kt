package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import androidx.core.net.toUri
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
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.astrovm.crosstune.ui.theme.DarkColorScheme
import com.astrovm.crosstune.ui.theme.LightColorScheme
import java.io.File

/** One song in the widget, as Recent shows it, with its cover made small enough for a widget. */
internal data class WidgetSong(val title: String, val subtitle: String, val url: String, val cover: Bitmap?)

/** Places [CrosstuneWidget] on the home screen. */
class CrosstuneWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CrosstuneWidget()
}

/**
 * Crosstune in miniature: its logo and a button to open the copied link, then Recent, each song
 * with its cover, opening in the user's app with ▶ or shown in Crosstune with a tap. One row tall,
 * it's just the button; two columns wide, the name and ▶ make way for the songs. Colors follow the app's: the wallpaper's from Android 12, its own before.
 */
class CrosstuneWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(PILL, NARROW_LIST, LIST))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val songs = widgetSongs(context)
        provideContent { WidgetTheme { WidgetContent(songs) } }
    }

    internal companion object {
        val PILL = DpSize(110.dp, 40.dp)
        val NARROW_LIST = DpSize(110.dp, 110.dp)
        val LIST = DpSize(260.dp, 110.dp)

        /** Below this height there's only room for the button. */
        val LIST_MIN_HEIGHT = 100.dp

        /** Below this width the songs need the whole row. */
        val FULL_ROW_MIN_WIDTH = 250.dp

        /** Covers are drawn at most this big, so a full Recent stays within a widget's memory. */
        const val COVER_PIXELS = 96

        private val fallbackColors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme)

        @Composable
        fun WidgetTheme(content: @Composable () -> Unit) =
            GlanceTheme(colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else fallbackColors, content = content)

        /** Recent, newest first, with the covers the app already saved, or downloads them. */
        suspend fun widgetSongs(context: Context): List<WidgetSong> {
            val preferences = context.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val artwork = ArtworkLoader(httpClient(), cacheDir = File(context.cacheDir, "artwork"))
            return HistoryStore(preferences).load().map { entry ->
                val details = listOf(
                    if (entry.link.type == ItemType.TRACK) "" else context.getString(entry.link.type.labelRes),
                    entry.metadata.artist,
                    context.getString(entry.link.service.labelRes)
                ).filter { it.isNotBlank() }.joinToString(" · ")
                val cover = entry.metadata.artworkUrl?.let { artwork.load(it) }?.asAndroidBitmap()?.scale(COVER_PIXELS, COVER_PIXELS)
                WidgetSong(entry.metadata.title, details, entry.link.url, cover)
            }
        }
    }
}

@Composable
internal fun WidgetContent(songs: List<WidgetSong>) {
    val context = LocalContext.current
    val paste = actionStartActivity(
        Intent(MainActivity.ACTION_PASTE_FROM_CLIPBOARD).setClassName(context, MainActivity.PASTE_ALIAS)
    )
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(GlanceTheme.colors.widgetBackground)
    ) {
        val size = LocalSize.current
        val wide = size.width >= CrosstuneWidget.FULL_ROW_MIN_WIDTH
        if (size.height < CrosstuneWidget.LIST_MIN_HEIGHT) {
            // One row tall: the whole widget opens the copied link.
            Row(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp).clickable(paste),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Logo()
                Spacer(GlanceModifier.width(10.dp))
                Text(
                    context.getString(R.string.shortcut_paste_short),
                    maxLines = 2,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                )
            }
        } else {
            Column(modifier = GlanceModifier.fillMaxSize().padding(start = 14.dp, end = 6.dp, top = 6.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo()
                    Spacer(GlanceModifier.width(10.dp))
                    Text(
                        if (wide) context.getString(R.string.app_name) else "",
                        modifier = GlanceModifier.defaultWeight(),
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    )
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_content_paste),
                        contentDescription = context.getString(R.string.shortcut_paste_long),
                        onClick = paste,
                        backgroundColor = GlanceTheme.colors.secondaryContainer,
                        contentColor = GlanceTheme.colors.onSecondaryContainer
                    )
                }
                if (songs.isEmpty()) {
                    Text(
                        context.getString(R.string.empty_hint),
                        modifier = GlanceModifier.padding(top = 8.dp, end = 8.dp),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp)
                    )
                } else {
                    LazyColumn {
                        items(songs, itemId = { it.url.hashCode().toLong() }) { song -> SongRow(song, wide) }
                    }
                }
            }
        }
    }
}

/** The app's note and arrow, each in its own color like in the app. */
@Composable
private fun Logo() {
    Box(modifier = GlanceModifier.size(28.dp)) {
        Image(ImageProvider(R.drawable.ic_logo_note), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface), modifier = GlanceModifier.fillMaxSize())
        Image(ImageProvider(R.drawable.ic_logo_arrow), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.primary), modifier = GlanceModifier.fillMaxSize())
    }
}

/** A Recent song: a tap shows it in Crosstune, ▶ opens it in the user's app when [withOpen]. */
@Composable
private fun SongRow(song: WidgetSong, withOpen: Boolean) {
    val context = LocalContext.current
    val show = Intent(Intent.ACTION_VIEW, song.url.toUri()).setClass(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_SHOW_SONG, true)
    val open = Intent(MainActivity.ACTION_OPEN_RECENT, song.url.toUri()).setClass(context, MainActivity::class.java)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(actionStartActivity(show)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverModifier = GlanceModifier.size(44.dp).cornerRadius(10.dp)
        if (song.cover != null) {
            Image(ImageProvider(song.cover), contentDescription = null, modifier = coverModifier)
        } else {
            Box(modifier = coverModifier.background(GlanceTheme.colors.surfaceVariant), contentAlignment = Alignment.Center) {
                Image(
                    ImageProvider(R.drawable.ic_music_note),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.size(20.dp)
                )
            }
        }
        Column(modifier = GlanceModifier.defaultWeight().padding(start = 12.dp, end = 8.dp)) {
            Text(song.title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium))
            Text(song.subtitle, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
        if (withOpen) CircleIconButton(
            imageProvider = ImageProvider(R.drawable.ic_play),
            contentDescription = context.getString(R.string.history_open, song.title),
            onClick = actionStartActivity(open),
            backgroundColor = null,
            contentColor = GlanceTheme.colors.onSurface,
            modifier = GlanceModifier.semantics { contentDescription = context.getString(R.string.history_open, song.title) }
        )
    }
}
