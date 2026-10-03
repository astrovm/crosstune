package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.net.toUri

/**
 * Keeps Crosstune's dynamic shortcuts in step with the app: the latest songs, and share sheet
 * targets that open a shared link straight in one app. Android drops shortcuts hidden from the
 * launcher, so the targets show there too, after the songs, where they open the copied link.
 */
internal class AppShortcuts(private val context: Context, private val loadArtwork: suspend (String) -> ImageBitmap?) {

    suspend fun update(recent: List<HistoryEntry>, shareTargets: List<MusicService>) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val songs = recent.take(MAX_RECENT).mapIndexed { rank, entry -> songShortcut(entry, rank) }
        val targets = shareTargets.take(MAX_SHARE_TARGETS).mapIndexed { rank, service -> shareTarget(service, rank) }
        // Some devices allow as few as five per activity, counting the one in shortcuts.xml.
        manager.dynamicShortcuts = (songs + targets).take(manager.maxShortcutCountPerActivity - manager.manifestShortcuts.size)
    }

    /** Opens the song like tapping its link, so it goes wherever the user listens. */
    private suspend fun songShortcut(entry: HistoryEntry, rank: Int): ShortcutInfo {
        val cover = entry.metadata.artworkUrl?.let { loadArtwork(it) }
        return ShortcutInfo.Builder(context, RECENT_PREFIX + entry.link.url)
            .setShortLabel(entry.metadata.title)
            .setIcon(
                cover?.let { Icon.createWithBitmap(it.asAndroidBitmap()) }
                    ?: Icon.createWithResource(context, R.drawable.ic_shortcut_open_link)
            )
            .setIntent(Intent(Intent.ACTION_VIEW, entry.link.url.toUri()).setClass(context, MainActivity::class.java))
            .setRank(rank)
            .build()
    }

    private fun shareTarget(service: MusicService, rank: Int): ShortcutInfo {
        val id = SHARE_PREFIX + service.name
        // The share sheet sends the shared link instead; from the launcher it opens the copied one.
        val openCopied = Intent(MainActivity.ACTION_PASTE_FROM_CLIPBOARD)
            .setClassName(context, MainActivity.PASTE_ALIAS)
            .putExtra(Intent.EXTRA_SHORTCUT_ID, id)
        return ShortcutInfo.Builder(context, id)
            // Just the app's name: "Open in …" got cut off to "Open in You…" for both YouTube Music
            // and YouTube, and Android badges the icon with Crosstune's, which says the rest. In
            // launchers it sits under "Open copied link".
            .setShortLabel(context.getString(service.labelRes))
            .setIcon(Icon.createWithResource(context, service.iconRes))
            .setCategories(setOf(SHARE_CATEGORY))
            .setIntent(openCopied)
            .setRank(MAX_RECENT + rank)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setLongLived(true) }
            .build()
    }

    companion object {
        const val MAX_RECENT = 3
        /** Android's top row is shared with people and chats, so Crosstune takes little of it. */
        const val MAX_SHARE_TARGETS = 2
        private const val RECENT_PREFIX = "recent:"
        private const val SHARE_PREFIX = "open_in:"

        /** Must match the share-target category in res/xml/shortcuts.xml. */
        const val SHARE_CATEGORY = "com.astrovm.crosstune.category.OPEN_IN"

        /** Where a share sheet target, or the same shortcut from the launcher, opens the link, if one was used. */
        fun chosenDestination(intent: Intent): Destination? {
            val shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID) ?: return null
            val name = shortcutId.removePrefix(SHARE_PREFIX).takeIf { it != shortcutId } ?: return null
            return MusicService.entries.firstOrNull { it.name == name }?.let(Destination::Service)
        }
    }
}
