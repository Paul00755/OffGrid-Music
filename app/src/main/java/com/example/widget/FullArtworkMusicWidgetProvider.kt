package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.example.service.PlaybackManager

class FullArtworkMusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = WidgetUpdateManager.getAuthoritativeState(context, PlaybackManager.activeInstance?.playbackState?.value)
        WidgetUpdateManager.updateAllWidgets(context, state)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val state = WidgetUpdateManager.getAuthoritativeState(context, PlaybackManager.activeInstance?.playbackState?.value)
        WidgetUpdateManager.updateAllWidgets(context, state)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            WidgetUpdateManager.ACTION_WIDGET_PLAY_PAUSE,
            WidgetUpdateManager.ACTION_WIDGET_NEXT,
            WidgetUpdateManager.ACTION_WIDGET_PREV,
            WidgetUpdateManager.ACTION_WIDGET_FAVORITE,
            WidgetUpdateManager.ACTION_WIDGET_SEEK -> {
                WidgetUpdateManager.handleWidgetAction(context, intent)
            }
        }
    }
}
