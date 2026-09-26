package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.service.PlaybackManager
import java.util.Locale

class MusicAppWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_PLAY_PAUSE = "com.example.widget.ACTION_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.example.widget.ACTION_NEXT"
        const val ACTION_WIDGET_PREV = "com.example.widget.ACTION_PREV"
        const val ACTION_WIDGET_FAVORITE = "com.example.widget.ACTION_FAVORITE"
        const val ACTION_WIDGET_SHUFFLE = "com.example.widget.ACTION_SHUFFLE"
        const val ACTION_WIDGET_REPEAT = "com.example.widget.ACTION_REPEAT"
        const val ACTION_WIDGET_SEEK_FORWARD = "com.example.widget.ACTION_SEEK_FORWARD"
        const val ACTION_WIDGET_SEEK_BACKWARD = "com.example.widget.ACTION_SEEK_BACKWARD"

        fun updateAllWidgets(
            context: Context,
            title: String = "No Song Playing",
            artist: String = "Offgrid Player",
            isPlaying: Boolean = false,
            positionMs: Long = 0L,
            durationMs: Long = 1000L,
            isFavorite: Boolean = false,
            isShuffleEnabled: Boolean = false,
            artworkBitmap: android.graphics.Bitmap? = null
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, MusicAppWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName) ?: return

            if (appWidgetIds.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget_music_player)

            // Update text
            views.setTextViewText(R.id.widget_song_title, title)
            views.setTextViewText(R.id.widget_song_artist, artist)

            // Artwork
            val roundedArt = WidgetBitmapRenderer.getRoundedArtwork(context, artworkBitmap, 180, 12f)
            views.setImageViewBitmap(R.id.widget_album_art, roundedArt)

            // Format time
            val currentSec = positionMs / 1000
            val durationSec = (if (durationMs > 0) durationMs else 1000L) / 1000
            val formattedCurrent = String.format(Locale.getDefault(), "%d:%02d", currentSec / 60, currentSec % 60)
            val formattedTotal = String.format(Locale.getDefault(), "%d:%02d", durationSec / 60, durationSec % 60)

            views.setTextViewText(R.id.widget_time_elapsed, formattedCurrent)
            views.setTextViewText(R.id.widget_time_total, formattedTotal)

            val progress = if (durationMs > 0) ((positionMs.toDouble() / durationMs.toDouble()) * 100).toInt() else 0
            views.setProgressBar(R.id.widget_progress_bar, 100, progress.coerceIn(0, 100), false)

            val progressRatio = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            val waveformBmp = WidgetBitmapRenderer.renderWaveformBarBitmap(
                context = context,
                widthPx = 380,
                heightPx = 28,
                progressRatio = progressRatio,
                accentColor = WidgetBitmapRenderer.getCachedAccentColor()
            )
            views.setImageViewBitmap(R.id.widget_waveform_progress, waveformBmp)

            // Update Play / Pause Icon
            val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            views.setImageViewResource(R.id.widget_btn_play_pause, playPauseIcon)

            // Update Favorite Icon
            val favoriteIcon = if (isFavorite) R.drawable.ic_widget_favorite_active else R.drawable.ic_favorite_border
            views.setImageViewResource(R.id.widget_btn_favorite, favoriteIcon)

            // Open App PendingIntent - Only for Album Art, Title, and Artist
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_album_art, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_song_title, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_song_artist, openAppPendingIntent)

            // Button Broadcast Pending Intents
            views.setOnClickPendingIntent(R.id.widget_btn_play_pause, getBroadcastPendingIntent(context, ACTION_WIDGET_PLAY_PAUSE, 1))
            views.setOnClickPendingIntent(R.id.widget_btn_next, getBroadcastPendingIntent(context, ACTION_WIDGET_NEXT, 2))
            views.setOnClickPendingIntent(R.id.widget_btn_prev, getBroadcastPendingIntent(context, ACTION_WIDGET_PREV, 3))
            views.setOnClickPendingIntent(R.id.widget_btn_favorite, getBroadcastPendingIntent(context, ACTION_WIDGET_FAVORITE, 4))
            views.setOnClickPendingIntent(R.id.widget_btn_shuffle, getBroadcastPendingIntent(context, ACTION_WIDGET_SHUFFLE, 5))
            views.setOnClickPendingIntent(R.id.widget_btn_repeat, getBroadcastPendingIntent(context, ACTION_WIDGET_REPEAT, 6))
            views.setOnClickPendingIntent(R.id.widget_time_elapsed, getBroadcastPendingIntent(context, ACTION_WIDGET_SEEK_BACKWARD, 7))
            views.setOnClickPendingIntent(R.id.widget_time_total, getBroadcastPendingIntent(context, ACTION_WIDGET_SEEK_FORWARD, 8))
            views.setOnClickPendingIntent(R.id.widget_seek_backward, getBroadcastPendingIntent(context, ACTION_WIDGET_SEEK_BACKWARD, 9))
            views.setOnClickPendingIntent(R.id.widget_seek_forward, getBroadcastPendingIntent(context, ACTION_WIDGET_SEEK_FORWARD, 10))

            appWidgetManager.updateAppWidget(appWidgetIds, views)
        }

        private fun getBroadcastPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MusicAppWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val playbackManager = PlaybackManager.activeInstance
        if (playbackManager != null) {
            WidgetUpdateManager.updateAllWidgets(context, playbackManager.playbackState.value)
        } else {
            updateAllWidgets(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val playbackManager = PlaybackManager.activeInstance

        when (intent.action) {
            ACTION_WIDGET_PLAY_PAUSE -> playbackManager?.togglePlayPause()
            ACTION_WIDGET_NEXT -> playbackManager?.skipToNext()
            ACTION_WIDGET_PREV -> playbackManager?.skipToPrevious()
            ACTION_WIDGET_FAVORITE -> {
                playbackManager?.toggleCurrentSongFavorite()
            }
            ACTION_WIDGET_SHUFFLE -> playbackManager?.toggleShuffle()
            ACTION_WIDGET_REPEAT -> playbackManager?.toggleRepeat()
            ACTION_WIDGET_SEEK_FORWARD -> playbackManager?.seekForward()
            ACTION_WIDGET_SEEK_BACKWARD -> playbackManager?.seekBackward()
        }

        if (playbackManager != null) {
            WidgetUpdateManager.updateAllWidgets(context, playbackManager.playbackState.value)
        }
    }
}
