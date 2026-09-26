package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.service.PlaybackManager

class CircularMusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val playbackManager = PlaybackManager.activeInstance
        if (playbackManager != null) {
            WidgetUpdateManager.updateAllWidgets(context, playbackManager.playbackState.value)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val playbackManager = PlaybackManager.activeInstance

        when (intent.action) {
            WidgetUpdateManager.ACTION_WIDGET_PLAY_PAUSE -> playbackManager?.togglePlayPause()
            WidgetUpdateManager.ACTION_WIDGET_NEXT -> playbackManager?.skipToNext()
            WidgetUpdateManager.ACTION_WIDGET_PREV -> playbackManager?.skipToPrevious()
            WidgetUpdateManager.ACTION_WIDGET_FAVORITE -> {
                playbackManager?.toggleCurrentSongFavorite()
            }
            WidgetUpdateManager.ACTION_WIDGET_SHUFFLE -> playbackManager?.toggleShuffle()
            WidgetUpdateManager.ACTION_WIDGET_SEEK_FORWARD -> playbackManager?.seekForward()
            WidgetUpdateManager.ACTION_WIDGET_SEEK_BACKWARD -> playbackManager?.seekBackward()
        }

        if (playbackManager != null) {
            WidgetUpdateManager.updateAllWidgets(context, playbackManager.playbackState.value)
        }
    }
}
