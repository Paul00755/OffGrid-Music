package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.service.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

object WidgetUpdateManager {

    const val ACTION_WIDGET_PLAY_PAUSE = "com.example.widget.ACTION_PLAY_PAUSE"
    const val ACTION_WIDGET_NEXT = "com.example.widget.ACTION_NEXT"
    const val ACTION_WIDGET_PREV = "com.example.widget.ACTION_PREV"
    const val ACTION_WIDGET_FAVORITE = "com.example.widget.ACTION_FAVORITE"
    const val ACTION_WIDGET_SHUFFLE = "com.example.widget.ACTION_SHUFFLE"
    const val ACTION_WIDGET_REPEAT = "com.example.widget.ACTION_REPEAT"
    const val ACTION_WIDGET_SEEK_FORWARD = "com.example.widget.ACTION_SEEK_FORWARD"
    const val ACTION_WIDGET_SEEK_BACKWARD = "com.example.widget.ACTION_SEEK_BACKWARD"

    fun updateAllWidgets(context: Context, state: PlaybackState) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
                val compactComponent = ComponentName(context, CompactMusicWidgetProvider::class.java)
                val circularComponent = ComponentName(context, CircularMusicWidgetProvider::class.java)
                val wideComponent = ComponentName(context, WideMusicWidgetProvider::class.java)
                val defaultComponent = ComponentName(context, MusicAppWidgetProvider::class.java)

                val compactIds = appWidgetManager.getAppWidgetIds(compactComponent) ?: IntArray(0)
                val circularIds = appWidgetManager.getAppWidgetIds(circularComponent) ?: IntArray(0)
                val wideIds = appWidgetManager.getAppWidgetIds(wideComponent) ?: IntArray(0)
                val defaultIds = appWidgetManager.getAppWidgetIds(defaultComponent) ?: IntArray(0)

                if (compactIds.isEmpty() && circularIds.isEmpty() && wideIds.isEmpty() && defaultIds.isEmpty()) {
                    return@launch
                }

                val currentSong = state.currentSong
                val artworkBitmap = WidgetBitmapRenderer.getOrLoadSongBitmap(context, currentSong)
                val accentColor = WidgetBitmapRenderer.getCachedAccentColor()

                if (compactIds.isNotEmpty()) {
                    updateCompactWidgets(context, appWidgetManager, state, artworkBitmap, accentColor)
                }
                if (circularIds.isNotEmpty()) {
                    updateCircularWidgets(context, appWidgetManager, state, artworkBitmap, accentColor)
                }
                if (wideIds.isNotEmpty()) {
                    updateWideWidgets(context, appWidgetManager, state, artworkBitmap, accentColor)
                }
                if (defaultIds.isNotEmpty()) {
                    MusicAppWidgetProvider.updateAllWidgets(
                        context = context,
                        title = currentSong?.displayTitle ?: "Offgrid Player",
                        artist = currentSong?.displayArtist ?: "Offline Audio",
                        isPlaying = state.isPlaying,
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        isFavorite = currentSong?.isFavorite == true,
                        isShuffleEnabled = state.isShuffleEnabled,
                        artworkBitmap = artworkBitmap
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val widgetScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    private var progressJob: kotlinx.coroutines.Job? = null
    private var lastUpdatedSec = -1L

    fun updateWidgetsProgress(context: Context, positionMs: Long, durationMs: Long) {
        val currentSec = (positionMs / 1000).coerceAtLeast(0L)
        if (currentSec == lastUpdatedSec) return
        lastUpdatedSec = currentSec

        // Cancel previous progress job if still active to avoid stacking IPC transactions
        if (progressJob?.isActive == true) return

        progressJob = widgetScope.launch {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
                val compactComponent = ComponentName(context, CompactMusicWidgetProvider::class.java)
                val circularComponent = ComponentName(context, CircularMusicWidgetProvider::class.java)
                val wideComponent = ComponentName(context, WideMusicWidgetProvider::class.java)
                val defaultComponent = ComponentName(context, MusicAppWidgetProvider::class.java)

                val compactIds = appWidgetManager.getAppWidgetIds(compactComponent) ?: IntArray(0)
                val circularIds = appWidgetManager.getAppWidgetIds(circularComponent) ?: IntArray(0)
                val wideIds = appWidgetManager.getAppWidgetIds(wideComponent) ?: IntArray(0)
                val defaultIds = appWidgetManager.getAppWidgetIds(defaultComponent) ?: IntArray(0)

                if (compactIds.isEmpty() && circularIds.isEmpty() && wideIds.isEmpty() && defaultIds.isEmpty()) {
                    return@launch
                }

                val durationSec = (if (durationMs > 0L) durationMs else 0L) / 1000
                val elapsedStr = formatTime(currentSec)
                val totalStr = formatTime(durationSec)
                val progress = if (durationMs > 0L) ((positionMs.toDouble() / durationMs.toDouble()) * 100).toInt().coerceIn(0, 100) else 0
                val progressRatio = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                val accentColor = WidgetBitmapRenderer.getCachedAccentColor()

                if (compactIds.isNotEmpty()) {
                    val views = RemoteViews(context.packageName, R.layout.widget_compact)
                    views.setTextViewText(R.id.widget_compact_time_elapsed, elapsedStr)
                    views.setTextViewText(R.id.widget_compact_time_total, totalStr)
                    views.setProgressBar(R.id.widget_compact_progress, 100, progress, false)
                    val waveBmp = WidgetBitmapRenderer.renderWaveformBarBitmap(
                        context, widthPx = 360, heightPx = 28, progressRatio = progressRatio, accentColor = accentColor
                    )
                    views.setImageViewBitmap(R.id.widget_compact_waveform_progress, waveBmp)
                    appWidgetManager.partiallyUpdateAppWidget(compactIds, views)
                }

                if (wideIds.isNotEmpty()) {
                    val views = RemoteViews(context.packageName, R.layout.widget_wide)
                    views.setTextViewText(R.id.widget_wide_time_elapsed, elapsedStr)
                    views.setTextViewText(R.id.widget_wide_time_total, totalStr)
                    views.setProgressBar(R.id.widget_wide_progress, 100, progress, false)
                    val waveBmp = WidgetBitmapRenderer.renderWaveformBarBitmap(
                        context, widthPx = 420, heightPx = 32, progressRatio = progressRatio, accentColor = accentColor
                    )
                    views.setImageViewBitmap(R.id.widget_wide_waveform_progress, waveBmp)
                    appWidgetManager.partiallyUpdateAppWidget(wideIds, views)
                }

                if (defaultIds.isNotEmpty()) {
                    val views = RemoteViews(context.packageName, R.layout.widget_music_player)
                    views.setTextViewText(R.id.widget_time_elapsed, elapsedStr)
                    views.setTextViewText(R.id.widget_time_total, totalStr)
                    views.setProgressBar(R.id.widget_progress_bar, 100, progress, false)
                    val waveBmp = WidgetBitmapRenderer.renderWaveformBarBitmap(
                        context, widthPx = 380, heightPx = 28, progressRatio = progressRatio, accentColor = accentColor
                    )
                    views.setImageViewBitmap(R.id.widget_waveform_progress, waveBmp)
                    appWidgetManager.partiallyUpdateAppWidget(defaultIds, views)
                }

                if (circularIds.isNotEmpty()) {
                    val views = RemoteViews(context.packageName, R.layout.widget_circular)
                    views.setTextViewText(R.id.widget_circular_time_elapsed, elapsedStr)
                    views.setTextViewText(R.id.widget_circular_time_total, totalStr)
                    val artworkBitmap = WidgetBitmapRenderer.getCachedArtwork()
                    val circularBitmap = WidgetBitmapRenderer.createCircularWidgetBitmap(
                        context = context,
                        sourceArtwork = artworkBitmap,
                        progressRatio = progressRatio,
                        accentColor = accentColor,
                        sizePx = 220
                    )
                    views.setImageViewBitmap(R.id.widget_circular_canvas, circularBitmap)
                    appWidgetManager.partiallyUpdateAppWidget(circularIds, views)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateCompactWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        state: PlaybackState,
        artworkBitmap: Bitmap?,
        accentColor: Int
    ) {
        val component = ComponentName(context, CompactMusicWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(component) ?: return
        if (widgetIds.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget_compact)
        val song = state.currentSong

        // Text
        views.setTextViewText(R.id.widget_compact_title, song?.displayTitle ?: "OffGrid Music")
        views.setTextViewText(R.id.widget_compact_subtitle, if (!song?.album.isNullOrBlank()) song?.album else "Offline Audio")
        views.setTextViewText(R.id.widget_compact_artist, song?.displayArtist ?: "Tap to play")

        // Progress
        val currentSec = state.positionMs / 1000
        val durationSec = (if (state.durationMs > 0) state.durationMs else 0L) / 1000
        views.setTextViewText(R.id.widget_compact_time_elapsed, formatTime(currentSec))
        views.setTextViewText(R.id.widget_compact_time_total, formatTime(durationSec))

        val progress = if (state.durationMs > 0) ((state.positionMs.toDouble() / state.durationMs.toDouble()) * 100).toInt() else 0
        views.setProgressBar(R.id.widget_compact_progress, 100, progress.coerceIn(0, 100), false)
        val progressRatio = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        val compactWaveBmp = WidgetBitmapRenderer.renderWaveformBarBitmap(
            context, widthPx = 360, heightPx = 28, progressRatio = progressRatio, accentColor = accentColor
        )
        views.setImageViewBitmap(R.id.widget_compact_waveform_progress, compactWaveBmp)

        // Rounded Artwork Bitmap & Blurred Background (from cache)
        val roundedArt = WidgetBitmapRenderer.getRoundedArtwork(context, artworkBitmap, 180, 12f)
        views.setImageViewBitmap(R.id.widget_compact_artwork, roundedArt)

        val blurredBg = WidgetBitmapRenderer.getBlurredBackground(context, artworkBitmap, 400, 240, 20f)
        views.setImageViewBitmap(R.id.widget_compact_bg_overlay, blurredBg)

        // Play / Pause Icon
        val playPauseRes = if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_compact_btn_play_pause, playPauseRes)

        // Favorite Icon
        val isFav = song?.isFavorite == true
        val favRes = if (isFav) R.drawable.ic_widget_favorite_active else R.drawable.ic_favorite_border
        views.setImageViewResource(R.id.widget_compact_btn_favorite, favRes)

        // Set Pending Intents - Only Album Art and Title/Artist open app
        setOnClickOpenApp(context, views, R.id.widget_compact_artwork)
        setOnClickOpenApp(context, views, R.id.widget_compact_title)
        setOnClickOpenApp(context, views, R.id.widget_compact_subtitle)
        setOnClickOpenApp(context, views, R.id.widget_compact_artist)

        setBroadcast(context, views, CompactMusicWidgetProvider::class.java, R.id.widget_compact_btn_play_pause, ACTION_WIDGET_PLAY_PAUSE, 101)
        setBroadcast(context, views, CompactMusicWidgetProvider::class.java, R.id.widget_compact_btn_next, ACTION_WIDGET_NEXT, 102)
        setBroadcast(context, views, CompactMusicWidgetProvider::class.java, R.id.widget_compact_btn_prev, ACTION_WIDGET_PREV, 103)
        setBroadcast(context, views, CompactMusicWidgetProvider::class.java, R.id.widget_compact_btn_favorite, ACTION_WIDGET_FAVORITE, 104)
        setBroadcast(context, views, CompactMusicWidgetProvider::class.java, R.id.widget_compact_time_elapsed, ACTION_WIDGET_SEEK_BACKWARD, 105)
        setBroadcast(context, views, CompactMusicWidgetProvider::class.java, R.id.widget_compact_time_total, ACTION_WIDGET_SEEK_FORWARD, 106)
        setBroadcast(context, views, CompactMusicWidgetProvider::class.java, R.id.widget_compact_seek_backward, ACTION_WIDGET_SEEK_BACKWARD, 107)
        setBroadcast(context, views, CompactMusicWidgetProvider::class.java, R.id.widget_compact_seek_forward, ACTION_WIDGET_SEEK_FORWARD, 108)

        appWidgetManager.updateAppWidget(widgetIds, views)
    }

    private fun updateCircularWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        state: PlaybackState,
        artworkBitmap: Bitmap?,
        accentColor: Int
    ) {
        val component = ComponentName(context, CircularMusicWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(component) ?: return
        if (widgetIds.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget_circular)
        val song = state.currentSong

        val progressRatio = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()) else 0f
        val circularBitmap = WidgetBitmapRenderer.createCircularWidgetBitmap(
            context = context,
            sourceArtwork = artworkBitmap,
            progressRatio = progressRatio,
            accentColor = accentColor,
            sizePx = 480
        )
        views.setImageViewBitmap(R.id.widget_circular_canvas, circularBitmap)

        // Text inside circle - Song Title and Album Name right below it
        views.setTextViewText(R.id.widget_circular_title, song?.displayTitle ?: "OffGrid Music")
        views.setTextViewText(R.id.widget_circular_subtitle, if (!song?.album.isNullOrBlank()) song?.album else "Offline Audio")

        val currentSec = state.positionMs / 1000
        val durationSec = (if (state.durationMs > 0) state.durationMs else 0L) / 1000
        views.setTextViewText(R.id.widget_circular_time_elapsed, formatTime(currentSec))
        views.setTextViewText(R.id.widget_circular_time_total, formatTime(durationSec))

        // Play / Pause Icon
        val playPauseRes = if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_circular_btn_play_pause, playPauseRes)

        // Set Pending Intents - Title/Album opens app
        setOnClickOpenApp(context, views, R.id.widget_circular_title)
        setOnClickOpenApp(context, views, R.id.widget_circular_subtitle)

        setBroadcast(context, views, CircularMusicWidgetProvider::class.java, R.id.widget_circular_btn_play_pause, ACTION_WIDGET_PLAY_PAUSE, 201)
        setBroadcast(context, views, CircularMusicWidgetProvider::class.java, R.id.widget_circular_btn_next, ACTION_WIDGET_NEXT, 202)
        setBroadcast(context, views, CircularMusicWidgetProvider::class.java, R.id.widget_circular_btn_prev, ACTION_WIDGET_PREV, 203)
        setBroadcast(context, views, CircularMusicWidgetProvider::class.java, R.id.widget_circular_time_elapsed, ACTION_WIDGET_SEEK_BACKWARD, 204)
        setBroadcast(context, views, CircularMusicWidgetProvider::class.java, R.id.widget_circular_time_total, ACTION_WIDGET_SEEK_FORWARD, 205)

        appWidgetManager.updateAppWidget(widgetIds, views)
    }

    private fun updateWideWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        state: PlaybackState,
        artworkBitmap: Bitmap?,
        accentColor: Int
    ) {
        val component = ComponentName(context, WideMusicWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(component) ?: return
        if (widgetIds.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget_wide)
        val song = state.currentSong

        // Text & Progress
        views.setTextViewText(R.id.widget_wide_title, song?.displayTitle ?: "OffGrid Music")
        views.setTextViewText(R.id.widget_wide_subtitle, if (!song?.album.isNullOrBlank()) song?.album else "Offline Audio")
        views.setTextViewText(R.id.widget_wide_artist, song?.displayArtist ?: "Tap to play")

        val currentSec = state.positionMs / 1000
        val durationSec = (if (state.durationMs > 0) state.durationMs else 0L) / 1000
        views.setTextViewText(R.id.widget_wide_time_elapsed, formatTime(currentSec))
        views.setTextViewText(R.id.widget_wide_time_total, formatTime(durationSec))

        val progress = if (state.durationMs > 0) ((state.positionMs.toDouble() / state.durationMs.toDouble()) * 100).toInt() else 0
        views.setProgressBar(R.id.widget_wide_progress, 100, progress.coerceIn(0, 100), false)
        val progressRatio = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        val wideWaveBmp = WidgetBitmapRenderer.renderWaveformBarBitmap(
            context, widthPx = 420, heightPx = 32, progressRatio = progressRatio, accentColor = accentColor
        )
        views.setImageViewBitmap(R.id.widget_wide_waveform_progress, wideWaveBmp)

        // Rounded Artwork Bitmap & Blurred Background (from cache)
        val roundedArt = WidgetBitmapRenderer.getRoundedArtwork(context, artworkBitmap, 200, 12f)
        views.setImageViewBitmap(R.id.widget_wide_artwork, roundedArt)

        val blurredBg = WidgetBitmapRenderer.getBlurredBackground(context, artworkBitmap, 480, 240, 20f)
        views.setImageViewBitmap(R.id.widget_wide_background_overlay, blurredBg)

        // Play / Pause Icon
        val playPauseRes = if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_wide_btn_play_pause, playPauseRes)

        // Favorite Icon
        val isFav = song?.isFavorite == true
        val favRes = if (isFav) R.drawable.ic_widget_favorite_active else R.drawable.ic_favorite_border
        views.setImageViewResource(R.id.widget_wide_btn_favorite, favRes)

        // Set Pending Intents - Only Album Art and Title/Artist open app
        setOnClickOpenApp(context, views, R.id.widget_wide_artwork)
        setOnClickOpenApp(context, views, R.id.widget_wide_title)
        setOnClickOpenApp(context, views, R.id.widget_wide_subtitle)
        setOnClickOpenApp(context, views, R.id.widget_wide_artist)

        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_btn_play_pause, ACTION_WIDGET_PLAY_PAUSE, 301)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_btn_next, ACTION_WIDGET_NEXT, 302)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_btn_prev, ACTION_WIDGET_PREV, 303)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_btn_favorite, ACTION_WIDGET_FAVORITE, 304)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_btn_shuffle, ACTION_WIDGET_SHUFFLE, 305)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_time_elapsed, ACTION_WIDGET_SEEK_BACKWARD, 306)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_time_total, ACTION_WIDGET_SEEK_FORWARD, 307)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_seek_backward, ACTION_WIDGET_SEEK_BACKWARD, 308)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_seek_forward, ACTION_WIDGET_SEEK_FORWARD, 309)

        appWidgetManager.updateAppWidget(widgetIds, views)
    }

    private fun setOnClickOpenApp(context: Context, views: RemoteViews, viewId: Int) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pendingIntent)
    }

    private fun setBroadcast(context: Context, views: RemoteViews, providerClass: Class<*>, viewId: Int, action: String, requestCode: Int) {
        val intent = Intent(context, providerClass).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pendingIntent)
    }

    private fun formatTime(sec: Long): String {
        val mins = sec / 60
        val remainingSec = sec % 60
        return String.format(Locale.getDefault(), "%d:%02d", mins, remainingSec)
    }
}
