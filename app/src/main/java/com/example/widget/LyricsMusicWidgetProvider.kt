package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.example.service.PlaybackManager
import com.example.service.PlaybackState

class LyricsMusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = PlaybackManager.activeInstance?.playbackState?.value ?: PlaybackState()
        WidgetUpdateManager.updateAllWidgets(context, state)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val state = PlaybackManager.activeInstance?.playbackState?.value ?: PlaybackState()
        WidgetUpdateManager.updateAllWidgets(context, state)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action.startsWith("com.example.widget.ACTION_")) {
            WidgetUpdateManager.handleWidgetAction(context, intent)
        }
    }
}
