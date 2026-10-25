package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.service.PlaybackManager

class MediumMusicWidgetProvider : AppWidgetProvider() {

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
            WidgetUpdateManager.ACTION_WIDGET_NEXT -> playbackManager?.skipToNext()
            WidgetUpdateManager.ACTION_WIDGET_PREV -> playbackManager?.skipToPrevious()
            WidgetUpdateManager.ACTION_WIDGET_FAVORITE -> {
                val currentSong = playbackManager?.playbackState?.value?.currentSong
                if (currentSong != null) {
                    WidgetUpdateManager.toggleFavoriteSong(context, currentSong.id)
                }
            }
        }

        val state = playbackManager?.playbackState?.value ?: com.example.service.PlaybackState()
        WidgetUpdateManager.updateAllWidgets(context, state)
    }
}
