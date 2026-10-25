package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.service.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

object WidgetUpdateManager {

    const val ACTION_WIDGET_PLAY_PAUSE = "com.example.widget.ACTION_PLAY_PAUSE"
    const val ACTION_WIDGET_NEXT = "com.example.widget.ACTION_NEXT"
    const val ACTION_WIDGET_PREV = "com.example.widget.ACTION_PREV"
    const val ACTION_WIDGET_FAVORITE = "com.example.widget.ACTION_FAVORITE"

    private const val PREFS_NAME = "widget_playback_snapshot"
    private const val KEY_SONG_ID = "song_id"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_ALBUM = "album"
    private const val KEY_ALBUM_ID = "album_id"
    private const val KEY_DURATION = "duration"
    private const val KEY_POSITION = "position"
    private const val KEY_PATH = "path"
    private const val KEY_CONTENT_URI = "content_uri"
    private const val KEY_ARTWORK_URI = "artwork_uri"
    private const val KEY_IS_PLAYING = "is_playing"

    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedIsDynamicTheme: Boolean = true
    @Volatile
    private var cachedManualAccentArgb: Int = 0xFFFF6D3F.toInt()
    @Volatile
    private var lastThemeCheckMs: Long = 0L

    @Volatile
    private var cachedFavSongId: Long = -1L
    @Volatile
    private var cachedIsFavorite: Boolean = false

    private fun refreshThemePrefsIfNeeded(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastThemeCheckMs < 3000L) return
        lastThemeCheckMs = now
        try {
            val prefs = UserPreferencesRepository.getInstance(context.applicationContext)
            val exported = kotlinx.coroutines.runBlocking { prefs.exportUserSettings() }
            cachedIsDynamicTheme = exported.dynamicArtworkTheme
            cachedManualAccentArgb = when (exported.accentColorPreset.uppercase()) {
                "PURPLE" -> android.graphics.Color.parseColor("#BB86FC")
                "PINK" -> android.graphics.Color.parseColor("#FF4081")
                "CYAN" -> android.graphics.Color.parseColor("#00E5FF")
                "GREEN" -> android.graphics.Color.parseColor("#00E676")
                else -> android.graphics.Color.parseColor("#FF6D3F")
            }
        } catch (_: Exception) {}
    }

    fun forceRefreshTheme(context: Context, state: PlaybackState) {
        lastThemeCheckMs = 0L
        WidgetBitmapRenderer.clearCache()
        updateAllWidgets(context, state)
    }

    private fun savePlaybackSnapshot(context: Context, state: PlaybackState) {
        val song = state.currentSong ?: return
        try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_SONG_ID, song.id)
                .putString(KEY_TITLE, song.displayTitle)
                .putString(KEY_ARTIST, song.displayArtist)
                .putString(KEY_ALBUM, song.displayAlbum)
                .putLong(KEY_ALBUM_ID, song.albumId)
                .putLong(KEY_DURATION, if (state.durationMs > 0L) state.durationMs else song.durationMs)
                .putLong(KEY_POSITION, state.positionMs.coerceAtLeast(0L))
                .putString(KEY_PATH, song.path)
                .putString(KEY_CONTENT_URI, song.contentUriString)
                .putString(KEY_ARTWORK_URI, song.artworkUri)
                .putBoolean(KEY_IS_PLAYING, state.isPlaying)
                .apply()
        } catch (_: Exception) {}
    }

    private fun resolveEffectiveState(context: Context, state: PlaybackState): PlaybackState {
        if (state.currentSong != null) {
            savePlaybackSnapshot(context, state)
            return state
        }
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val songId = prefs.getLong(KEY_SONG_ID, -1L)
            if (songId != -1L) {
                val title = prefs.getString(KEY_TITLE, null) ?: return state
                val artist = prefs.getString(KEY_ARTIST, "Unknown Artist") ?: "Unknown Artist"
                val album = prefs.getString(KEY_ALBUM, "Unknown Album") ?: "Unknown Album"
                val albumId = prefs.getLong(KEY_ALBUM_ID, 0L)
                val duration = prefs.getLong(KEY_DURATION, 0L)
                val position = prefs.getLong(KEY_POSITION, 0L)
                val path = prefs.getString(KEY_PATH, "") ?: ""
                val contentUri = prefs.getString(KEY_CONTENT_URI, "") ?: ""
                val artworkUri = prefs.getString(KEY_ARTWORK_URI, null)

                val restoredSong = com.example.data.model.Song(
                    id = songId,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    durationMs = duration,
                    path = path,
                    contentUriString = contentUri,
                    artworkUri = artworkUri
                )
                return state.copy(
                    currentSong = restoredSong,
                    durationMs = duration,
                    positionMs = position
                )
            }
        } catch (_: Exception) {}
        return state
    }

    private fun checkIsFavorite(context: Context, songId: Long): Boolean {
        if (songId <= 0L) return false
        if (songId == cachedFavSongId) return cachedIsFavorite
        return try {
            val db = AppDatabase.getInstance(context.applicationContext)
            val fav: Boolean = kotlinx.coroutines.runBlocking {
                db.songDao().getSongById(songId)?.isFavorite == true
            }
            cachedFavSongId = songId
            cachedIsFavorite = fav
            fav
        } catch (_: Exception) {
            false
        }
    }

    fun toggleFavoriteSong(context: Context, songId: Long) {
        if (songId <= 0L) return
        widgetScope.launch {
            try {
                val db = AppDatabase.getInstance(context.applicationContext)
                val currentlyFav = db.songDao().getSongById(songId)?.isFavorite == true
                val newFav = !currentlyFav
                db.songDao().setFavorite(songId, newFav)
                cachedFavSongId = songId
                cachedIsFavorite = newFav

                val state = com.example.service.PlaybackManager.activeInstance?.playbackState?.value
                    ?: PlaybackState()
                performFullWidgetUpdate(context.applicationContext, state)
            } catch (_: Exception) {}
        }
    }

    fun updateAllWidgets(context: Context, state: PlaybackState) {
        val appContext = context.applicationContext
        widgetScope.launch {
            performFullWidgetUpdate(appContext, state)
        }
    }

    private fun performFullWidgetUpdate(context: Context, rawState: PlaybackState) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val fullIds = appWidgetManager.getAppWidgetIds(ComponentName(context, FullArtworkMusicWidgetProvider::class.java))
        val smallIds = appWidgetManager.getAppWidgetIds(ComponentName(context, SmallMusicWidgetProvider::class.java))
        val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MediumMusicWidgetProvider::class.java))
        val secondaryIds = appWidgetManager.getAppWidgetIds(ComponentName(context, SecondaryMusicWidgetProvider::class.java))

        if (fullIds.isEmpty() && smallIds.isEmpty() && mediumIds.isEmpty() && secondaryIds.isEmpty()) {
            return
        }

        val state = resolveEffectiveState(context, rawState)
        refreshThemePrefsIfNeeded(context)

        val song = state.currentSong
        val sourceBitmap = WidgetBitmapRenderer.getOrLoadSongBitmap(context, song)
        val themeColors = WidgetBitmapRenderer.getThemeColors(
            songBitmap = sourceBitmap,
            isDynamicTheme = cachedIsDynamicTheme,
            manualAccentColor = cachedManualAccentArgb
        )
        val isFavorite = if (song != null) checkIsFavorite(context, song.id) else false

        if (fullIds.isNotEmpty()) {
            updateFullArtworkWidgets(context, appWidgetManager, fullIds, state, sourceBitmap, themeColors, isFavorite)
        }
        if (smallIds.isNotEmpty()) {
            updateSmallWidgets(context, appWidgetManager, smallIds, state, sourceBitmap, themeColors, isFavorite)
        }
        if (mediumIds.isNotEmpty()) {
            updateMediumWidgets(context, appWidgetManager, mediumIds, state, sourceBitmap, themeColors, isFavorite)
        }
        if (secondaryIds.isNotEmpty()) {
            updateSecondaryWidgets(context, appWidgetManager, secondaryIds, state, sourceBitmap, themeColors, isFavorite)
        }
    }

    fun updateWidgetsProgress(context: Context, positionMs: Long, durationMs: Long) {
        val baseState = com.example.service.PlaybackManager.activeInstance?.playbackState?.value
            ?: PlaybackState()
        val updatedState = baseState.copy(
            positionMs = positionMs,
            durationMs = if (durationMs > 0L) durationMs else baseState.durationMs
        )
        updateWidgetProgressOnly(context, updatedState)
    }

    fun updateWidgetsProgress(context: Context, state: PlaybackState) {
        updateWidgetProgressOnly(context, state)
    }

    fun updateWidgetProgressOnly(context: Context, state: PlaybackState) {
        val appContext = context.applicationContext
        widgetScope.launch {
            performProgressOnlyWidgetUpdate(appContext, state)
        }
    }

    private fun performProgressOnlyWidgetUpdate(context: Context, rawState: PlaybackState) {
        try {
            val state = resolveEffectiveState(context, rawState)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val fullIds = appWidgetManager.getAppWidgetIds(ComponentName(context, FullArtworkMusicWidgetProvider::class.java))
            val smallIds = appWidgetManager.getAppWidgetIds(ComponentName(context, SmallMusicWidgetProvider::class.java))
            val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MediumMusicWidgetProvider::class.java))
            val secondaryIds = appWidgetManager.getAppWidgetIds(ComponentName(context, SecondaryMusicWidgetProvider::class.java))

            if (fullIds.isEmpty() && smallIds.isEmpty() && mediumIds.isEmpty() && secondaryIds.isEmpty()) {
                return
            }

            val song = state.currentSong
            val durationMs = if (state.durationMs > 0L) state.durationMs else (song?.durationMs ?: 0L)
            val posMs = state.positionMs.coerceAtLeast(0L)
            val progressRatio = if (durationMs > 0L) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

            val currentText = formatTime(posMs)
            val totalText = formatTime(durationMs)

            val sourceBitmap = WidgetBitmapRenderer.getOrLoadSongBitmap(context, song)
            val themeColors = WidgetBitmapRenderer.getThemeColors(
                songBitmap = sourceBitmap,
                isDynamicTheme = cachedIsDynamicTheme,
                manualAccentColor = cachedManualAccentArgb
            )
            val density = context.resources.displayMetrics.density

            if (fullIds.isNotEmpty()) {
                val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
                    context = context,
                    widthPx = (165 * density).toInt(),
                    heightPx = (14 * density).toInt(),
                    progressRatio = progressRatio,
                    accentColor = themeColors.accentColor
                )
                for (id in fullIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_full_artwork)
                    views.setTextViewText(R.id.widget_full_time_current, currentText)
                    views.setTextViewText(R.id.widget_full_time_total, totalText)
                    views.setImageViewBitmap(R.id.widget_full_progress, progBmp)
                    appWidgetManager.partiallyUpdateAppWidget(id, views)
                }
            }

            if (smallIds.isNotEmpty()) {
                val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
                    context = context,
                    widthPx = (165 * density).toInt(),
                    heightPx = (14 * density).toInt(),
                    progressRatio = progressRatio,
                    accentColor = themeColors.accentColor
                )
                for (id in smallIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_small)
                    views.setTextViewText(R.id.widget_small_time_current, currentText)
                    views.setTextViewText(R.id.widget_small_time_total, totalText)
                    views.setImageViewBitmap(R.id.widget_small_progress, progBmp)
                    appWidgetManager.partiallyUpdateAppWidget(id, views)
                }
            }

            if (mediumIds.isNotEmpty()) {
                val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
                    context = context,
                    widthPx = (210 * density).toInt(),
                    heightPx = (14 * density).toInt(),
                    progressRatio = progressRatio,
                    accentColor = themeColors.accentColor
                )
                for (id in mediumIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_medium)
                    views.setTextViewText(R.id.widget_medium_time_current, currentText)
                    views.setTextViewText(R.id.widget_medium_time_total, totalText)
                    views.setImageViewBitmap(R.id.widget_medium_progress, progBmp)
                    appWidgetManager.partiallyUpdateAppWidget(id, views)
                }
            }

            if (secondaryIds.isNotEmpty()) {
                val waveBmp = WidgetBitmapRenderer.renderWaveformProgressBar(
                    context = context,
                    widthPx = (210 * density).toInt(),
                    heightPx = (18 * density).toInt(),
                    progressRatio = progressRatio,
                    accentColor = themeColors.accentColor
                )
                for (id in secondaryIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_secondary)
                    views.setTextViewText(R.id.widget_secondary_time_current, currentText)
                    views.setTextViewText(R.id.widget_secondary_time_total, totalText)
                    views.setImageViewBitmap(R.id.widget_secondary_progress, waveBmp)
                    appWidgetManager.partiallyUpdateAppWidget(id, views)
                }
            }
        } catch (_: Exception) {}
    }

    private fun updateFullArtworkWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        state: PlaybackState,
        sourceBitmap: android.graphics.Bitmap?,
        themeColors: WidgetBitmapRenderer.WidgetThemeColors,
        isFavorite: Boolean
    ) {
        val song = state.currentSong
        val title = song?.displayTitle ?: "OffGrid Music"
        val subtitle = song?.displayArtist ?: "Tap to play music"
        val durationMs = if (state.durationMs > 0L) state.durationMs else (song?.durationMs ?: 0L)
        val posMs = state.positionMs.coerceAtLeast(0L)
        val progressRatio = if (durationMs > 0L) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

        val density = context.resources.displayMetrics.density
        val bgBmp = WidgetBitmapRenderer.renderFullBleedArtworkBackground(
            context = context,
            source = sourceBitmap,
            widthPx = (200 * density).toInt(),
            heightPx = (200 * density).toInt(),
            cornerRadiusDp = 24f,
            accentColor = themeColors.accentColor
        )
        val favBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = (32 * density).toInt(),
            isFavorite = isFavorite,
            accentColor = themeColors.accentColor
        )
        val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
            context = context,
            widthPx = (165 * density).toInt(),
            heightPx = (14 * density).toInt(),
            progressRatio = progressRatio,
            accentColor = themeColors.accentColor
        )
        val prevBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (36 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PREV
        )
        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (42 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying
        )
        val nextBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (36 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.NEXT
        )

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_full_artwork)
            views.setImageViewBitmap(R.id.widget_full_bg, bgBmp)
            views.setImageViewBitmap(R.id.widget_full_btn_favorite, favBmp)
            views.setTextViewText(R.id.widget_full_title, title)
            views.setTextViewText(R.id.widget_full_subtitle, subtitle)
            views.setImageViewBitmap(R.id.widget_full_progress, progBmp)
            views.setTextViewText(R.id.widget_full_time_current, formatTime(posMs))
            views.setTextViewText(R.id.widget_full_time_total, formatTime(durationMs))
            views.setImageViewBitmap(R.id.widget_full_btn_prev, prevBmp)
            views.setImageViewBitmap(R.id.widget_full_btn_play_pause, playPauseBmp)
            views.setImageViewBitmap(R.id.widget_full_btn_next, nextBmp)

            views.setOnClickPendingIntent(R.id.widget_full_root, createOpenAppIntent(context))
            views.setOnClickPendingIntent(R.id.widget_full_btn_favorite, createActionIntent(context, FullArtworkMusicWidgetProvider::class.java, ACTION_WIDGET_FAVORITE, 100))
            views.setOnClickPendingIntent(R.id.widget_full_btn_prev, createActionIntent(context, FullArtworkMusicWidgetProvider::class.java, ACTION_WIDGET_PREV, 101))
            views.setOnClickPendingIntent(R.id.widget_full_btn_play_pause, createActionIntent(context, FullArtworkMusicWidgetProvider::class.java, ACTION_WIDGET_PLAY_PAUSE, 102))
            views.setOnClickPendingIntent(R.id.widget_full_btn_next, createActionIntent(context, FullArtworkMusicWidgetProvider::class.java, ACTION_WIDGET_NEXT, 103))

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun updateSmallWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        state: PlaybackState,
        sourceBitmap: android.graphics.Bitmap?,
        themeColors: WidgetBitmapRenderer.WidgetThemeColors,
        isFavorite: Boolean
    ) {
        val song = state.currentSong
        val title = song?.displayTitle ?: "OffGrid Music"
        val subtitle = song?.displayArtist ?: "Tap to play music"
        val durationMs = if (state.durationMs > 0L) state.durationMs else (song?.durationMs ?: 0L)
        val posMs = state.positionMs.coerceAtLeast(0L)
        val progressRatio = if (durationMs > 0L) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

        val density = context.resources.displayMetrics.density
        val bgBmp = WidgetBitmapRenderer.renderCardBackground(
            context = context,
            widthPx = (200 * density).toInt(),
            heightPx = (200 * density).toInt(),
            cornerRadiusDp = 24f,
            bgColor = themeColors.containerBgColor,
            borderColor = themeColors.containerBorderColor
        )
        val artBmp = WidgetBitmapRenderer.renderRoundedArtwork(
            source = sourceBitmap,
            widthPx = (64 * density).toInt(),
            heightPx = (64 * density).toInt(),
            cornerRadiusDp = 14f,
            context = context
        )
        val favBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = (32 * density).toInt(),
            isFavorite = isFavorite,
            accentColor = themeColors.accentColor
        )
        val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
            context = context,
            widthPx = (165 * density).toInt(),
            heightPx = (14 * density).toInt(),
            progressRatio = progressRatio,
            accentColor = themeColors.accentColor
        )
        val prevBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (34 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PREV
        )
        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (40 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying
        )
        val nextBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (34 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.NEXT
        )

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            views.setImageViewBitmap(R.id.widget_small_bg, bgBmp)
            views.setImageViewBitmap(R.id.widget_small_artwork, artBmp)
            views.setImageViewBitmap(R.id.widget_small_btn_favorite, favBmp)
            views.setTextViewText(R.id.widget_small_title, title)
            views.setTextViewText(R.id.widget_small_subtitle, subtitle)
            views.setImageViewBitmap(R.id.widget_small_progress, progBmp)
            views.setTextViewText(R.id.widget_small_time_current, formatTime(posMs))
            views.setTextViewText(R.id.widget_small_time_total, formatTime(durationMs))
            views.setImageViewBitmap(R.id.widget_small_btn_prev, prevBmp)
            views.setImageViewBitmap(R.id.widget_small_btn_play_pause, playPauseBmp)
            views.setImageViewBitmap(R.id.widget_small_btn_next, nextBmp)

            views.setOnClickPendingIntent(R.id.widget_small_root, createOpenAppIntent(context))
            views.setOnClickPendingIntent(R.id.widget_small_btn_favorite, createActionIntent(context, SmallMusicWidgetProvider::class.java, ACTION_WIDGET_FAVORITE, 200))
            views.setOnClickPendingIntent(R.id.widget_small_btn_prev, createActionIntent(context, SmallMusicWidgetProvider::class.java, ACTION_WIDGET_PREV, 201))
            views.setOnClickPendingIntent(R.id.widget_small_btn_play_pause, createActionIntent(context, SmallMusicWidgetProvider::class.java, ACTION_WIDGET_PLAY_PAUSE, 202))
            views.setOnClickPendingIntent(R.id.widget_small_btn_next, createActionIntent(context, SmallMusicWidgetProvider::class.java, ACTION_WIDGET_NEXT, 203))

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun updateMediumWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        state: PlaybackState,
        sourceBitmap: android.graphics.Bitmap?,
        themeColors: WidgetBitmapRenderer.WidgetThemeColors,
        isFavorite: Boolean
    ) {
        val song = state.currentSong
        val title = song?.displayTitle ?: "OffGrid Music"
        val subtitle = song?.displayArtist ?: "Tap to play music"
        val durationMs = if (state.durationMs > 0L) state.durationMs else (song?.durationMs ?: 0L)
        val posMs = state.positionMs.coerceAtLeast(0L)
        val progressRatio = if (durationMs > 0L) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

        val density = context.resources.displayMetrics.density
        val bgBmp = WidgetBitmapRenderer.renderCardBackground(
            context = context,
            widthPx = (360 * density).toInt(),
            heightPx = (145 * density).toInt(),
            cornerRadiusDp = 24f,
            bgColor = themeColors.containerBgColor,
            borderColor = themeColors.containerBorderColor
        )
        val artBmp = WidgetBitmapRenderer.renderRoundedArtwork(
            source = sourceBitmap,
            widthPx = (115 * density).toInt(),
            heightPx = (115 * density).toInt(),
            cornerRadiusDp = 16f,
            context = context
        )
        val favBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = (30 * density).toInt(),
            isFavorite = isFavorite,
            accentColor = themeColors.accentColor
        )
        val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
            context = context,
            widthPx = (210 * density).toInt(),
            heightPx = (14 * density).toInt(),
            progressRatio = progressRatio,
            accentColor = themeColors.accentColor
        )
        val prevBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (34 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PREV
        )
        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (40 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying
        )
        val nextBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (34 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.NEXT
        )

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_medium)
            views.setImageViewBitmap(R.id.widget_medium_bg, bgBmp)
            views.setImageViewBitmap(R.id.widget_medium_artwork, artBmp)
            views.setImageViewBitmap(R.id.widget_medium_btn_favorite, favBmp)
            views.setTextViewText(R.id.widget_medium_title, title)
            views.setTextViewText(R.id.widget_medium_subtitle, subtitle)
            views.setImageViewBitmap(R.id.widget_medium_progress, progBmp)
            views.setTextViewText(R.id.widget_medium_time_current, formatTime(posMs))
            views.setTextViewText(R.id.widget_medium_time_total, formatTime(durationMs))
            views.setImageViewBitmap(R.id.widget_medium_btn_prev, prevBmp)
            views.setImageViewBitmap(R.id.widget_medium_btn_play_pause, playPauseBmp)
            views.setImageViewBitmap(R.id.widget_medium_btn_next, nextBmp)

            views.setOnClickPendingIntent(R.id.widget_medium_root, createOpenAppIntent(context))
            views.setOnClickPendingIntent(R.id.widget_medium_btn_favorite, createActionIntent(context, MediumMusicWidgetProvider::class.java, ACTION_WIDGET_FAVORITE, 300))
            views.setOnClickPendingIntent(R.id.widget_medium_btn_prev, createActionIntent(context, MediumMusicWidgetProvider::class.java, ACTION_WIDGET_PREV, 301))
            views.setOnClickPendingIntent(R.id.widget_medium_btn_play_pause, createActionIntent(context, MediumMusicWidgetProvider::class.java, ACTION_WIDGET_PLAY_PAUSE, 302))
            views.setOnClickPendingIntent(R.id.widget_medium_btn_next, createActionIntent(context, MediumMusicWidgetProvider::class.java, ACTION_WIDGET_NEXT, 303))

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun updateSecondaryWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        state: PlaybackState,
        sourceBitmap: android.graphics.Bitmap?,
        themeColors: WidgetBitmapRenderer.WidgetThemeColors,
        isFavorite: Boolean
    ) {
        val song = state.currentSong
        val title = song?.displayTitle ?: "OffGrid Music"
        val subtitle = song?.displayArtist ?: "Tap to play music"
        val durationMs = if (state.durationMs > 0L) state.durationMs else (song?.durationMs ?: 0L)
        val posMs = state.positionMs.coerceAtLeast(0L)
        val progressRatio = if (durationMs > 0L) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

        val density = context.resources.displayMetrics.density
        val bgBmp = WidgetBitmapRenderer.renderCardBackground(
            context = context,
            widthPx = (360 * density).toInt(),
            heightPx = (145 * density).toInt(),
            cornerRadiusDp = 24f,
            bgColor = themeColors.containerBgColor,
            borderColor = themeColors.containerBorderColor
        )
        val leftBleedArtBmp = WidgetBitmapRenderer.renderLeftBleedArtwork(
            source = sourceBitmap,
            widthPx = (128 * density).toInt(),
            heightPx = (145 * density).toInt(),
            leftCornerRadiusDp = 24f,
            context = context
        )
        val favBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = (30 * density).toInt(),
            isFavorite = isFavorite,
            accentColor = themeColors.accentColor
        )
        val waveProgBmp = WidgetBitmapRenderer.renderWaveformProgressBar(
            context = context,
            widthPx = (210 * density).toInt(),
            heightPx = (18 * density).toInt(),
            progressRatio = progressRatio,
            accentColor = themeColors.accentColor
        )
        val prevBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (34 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PREV
        )
        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (40 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying
        )
        val nextBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (34 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.NEXT
        )

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_secondary)
            views.setImageViewBitmap(R.id.widget_secondary_bg, bgBmp)
            views.setImageViewBitmap(R.id.widget_secondary_artwork, leftBleedArtBmp)
            views.setImageViewBitmap(R.id.widget_secondary_btn_favorite, favBmp)
            views.setTextViewText(R.id.widget_secondary_title, title)
            views.setTextViewText(R.id.widget_secondary_subtitle, subtitle)
            views.setImageViewBitmap(R.id.widget_secondary_progress, waveProgBmp)
            views.setTextViewText(R.id.widget_secondary_time_current, formatTime(posMs))
            views.setTextViewText(R.id.widget_secondary_time_total, formatTime(durationMs))
            views.setImageViewBitmap(R.id.widget_secondary_btn_prev, prevBmp)
            views.setImageViewBitmap(R.id.widget_secondary_btn_play_pause, playPauseBmp)
            views.setImageViewBitmap(R.id.widget_secondary_btn_next, nextBmp)

            views.setOnClickPendingIntent(R.id.widget_secondary_root, createOpenAppIntent(context))
            views.setOnClickPendingIntent(R.id.widget_secondary_btn_favorite, createActionIntent(context, SecondaryMusicWidgetProvider::class.java, ACTION_WIDGET_FAVORITE, 400))
            views.setOnClickPendingIntent(R.id.widget_secondary_btn_prev, createActionIntent(context, SecondaryMusicWidgetProvider::class.java, ACTION_WIDGET_PREV, 401))
            views.setOnClickPendingIntent(R.id.widget_secondary_btn_play_pause, createActionIntent(context, SecondaryMusicWidgetProvider::class.java, ACTION_WIDGET_PLAY_PAUSE, 402))
            views.setOnClickPendingIntent(R.id.widget_secondary_btn_next, createActionIntent(context, SecondaryMusicWidgetProvider::class.java, ACTION_WIDGET_NEXT, 403))

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun createOpenAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createActionIntent(
        context: Context,
        providerClass: Class<*>,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, providerClass).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format("%d:%02d", minutes, seconds)
    }
}
