package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.service.PlaybackManager

class PillMusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val playbackManager = PlaybackManager.activeInstance
        val state = playbackManager?.playbackState?.value ?: com.example.service.PlaybackState()
        WidgetUpdateManager.updateAllWidgets(context, state)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val playbackManager = PlaybackManager.activeInstance

        when (intent.action) {
            WidgetUpdateManager.ACTION_WIDGET_PLAY_PAUSE -> playbackManager?.togglePlayPause()
        }

        val state = playbackManager?.playbackState?.value ?: com.example.service.PlaybackState()
        WidgetUpdateManager.updateAllWidgets(context, state)
    }
}
