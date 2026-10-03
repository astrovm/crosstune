package com.astrovm.crosstune

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home screen widget that opens whatever music link is on the clipboard, like the Quick Settings tile. */
class OpenCopiedWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent().setClassName(context, MainActivity.PASTE_ALIAS)
            .setAction(MainActivity.ACTION_PASTE_FROM_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.widget_open_copied)
        views.setOnClickPendingIntent(R.id.widget_root, open)
        manager.updateAppWidget(ids, views)
    }
}
