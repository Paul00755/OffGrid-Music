package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.service.PlaybackManager
import com.example.service.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object WidgetUpdateManager {

    const val ACTION_WIDGET_PLAY_PAUSE = "com.example.widget.ACTION_PLAY_PAUSE"
    const val ACTION_WIDGET_NEXT = "com.example.widget.ACTION_NEXT"
    const val ACTION_WIDGET_PREV = "com.example.widget.ACTION_PREV"
    const val ACTION_WIDGET_FAVORITE = "com.example.widget.ACTION_FAVORITE"
    const val ACTION_WIDGET_SEEK = "com.example.widget.ACTION_SEEK"
    const val EXTRA_SEEK_FRACTION = "extra_seek_fraction"
    const val EXTRA_OPEN_NOW_PLAYING = "com.example.EXTRA_OPEN_NOW_PLAYING"
    const val EXTRA_WIDGET_ACTION = "com.example.EXTRA_WIDGET_ACTION"

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
    private val updateMutex = Mutex()
    private data class WidgetProgress(val context: Context, val positionMs: Long, val durationMs: Long)
    private val progressUpdates = Channel<WidgetProgress>(Channel.CONFLATED)

    init {
        widgetScope.launch {
            for (progress in progressUpdates) {
                val appContext = progress.context.applicationContext
                saveProgressSnapshotOnly(appContext, progress.positionMs, progress.durationMs)
                val baseState = PlaybackManager.activeInstance?.playbackState?.value ?: PlaybackState()
                val updatedState = baseState.copy(
                    positionMs = progress.positionMs,
                    durationMs = progress.durationMs.takeIf { it > 0L } ?: baseState.durationMs
                )
                updateMutex.withLock { performProgressOnlyWidgetUpdateInternal(appContext, updatedState) }
            }
        }
    }

    @Volatile
    private var cachedIsDynamicTheme: Boolean = true
    @Volatile
    private var cachedManualAccentArgb: Int = 0xFFFF6D3F.toInt()
    @Volatile
    private var cachedLastAccentColor: Int = 0xFFFF6D3F.toInt()
    @Volatile
    private var lastThemeCheckMs: Long = 0L

    @Volatile
    private var cachedFavSongId: Long = -1L
    @Volatile
    private var cachedIsFavorite: Boolean = false

    @Volatile
    private var lastRenderedTrackIdentity: String = ""
    @Volatile
    private var lastRenderedIsPlaying: Boolean = false
    @Volatile
    private var lastRenderedHasArtwork: Boolean = false

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
        lastRenderedTrackIdentity = ""
        WidgetBitmapRenderer.clearCache()
        updateAllWidgets(context, state)
    }

    /**
     * Returns the authoritative playback state by combining the live PlaybackManager state
     * (including its real-time `currentPositionMs` / `playbackPositionMs`) with the persisted snapshot.
     */
    fun getAuthoritativeState(context: Context, candidate: PlaybackState? = null): PlaybackState {
        val pm = PlaybackManager.activeInstance
        val livePmState = pm?.playbackState?.value
        val base = when {
            candidate?.currentSong != null -> candidate
            livePmState?.currentSong != null -> livePmState
            candidate != null -> candidate
            else -> PlaybackState()
        }
        val song = base.currentSong ?: return base

        val prefs = try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) { null }
        val snapSongId = prefs?.getLong(KEY_SONG_ID, -1L) ?: -1L
        val snapPos = if (snapSongId == song.id) (prefs?.getLong(KEY_POSITION, 0L) ?: 0L) else 0L
        val snapDur = if (snapSongId == song.id) (prefs?.getLong(KEY_DURATION, 0L) ?: 0L) else 0L

        val pmPos = if (pm != null && pm.playbackState.value.currentSong?.id == song.id) {
            maxOf(pm.currentPositionMs, pm.playbackPositionMs.value)
        } else 0L

        val livePos = when {
            pm != null && pm.playbackState.value.currentSong?.id == song.id && maxOf(pmPos, base.positionMs) > 0L -> maxOf(pmPos, base.positionMs)
            base.positionMs > 0L -> base.positionMs
            snapPos > 0L -> snapPos
            else -> 0L
        }.coerceAtLeast(0L)

        val liveDur = when {
            base.durationMs > 0L -> base.durationMs
            song.durationMs > 0L -> song.durationMs
            snapDur > 0L -> snapDur
            else -> 0L
        }
        val livePlaying = if (pm != null && pm.playbackState.value.currentSong?.id == song.id) {
            pm.playbackState.value.isPlaying
        } else {
            base.isPlaying
        }
        return base.copy(positionMs = livePos, durationMs = liveDur, isPlaying = livePlaying)
    }

    internal fun savePlaybackSnapshot(context: Context, state: PlaybackState) {
        val song = state.currentSong ?: return
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prevSongId = prefs.getLong(KEY_SONG_ID, -1L)
            val prevPos = prefs.getLong(KEY_POSITION, 0L)
            val pm = PlaybackManager.activeInstance
            val livePos = if (pm != null && pm.playbackState.value.currentSong?.id == song.id) {
                maxOf(state.positionMs, pm.currentPositionMs, pm.playbackPositionMs.value)
            } else {
                state.positionMs
            }
            val effectivePos = if (livePos > 0L) {
                livePos
            } else if (prevSongId == song.id && prevPos > 0L && pm == null) {
                prevPos
            } else {
                livePos.coerceAtLeast(0L)
            }
            val effectiveDur = if (state.durationMs > 0L) state.durationMs else song.durationMs

            prefs.edit()
                .putLong(KEY_SONG_ID, song.id)
                .putString(KEY_TITLE, song.displayTitle)
                .putString(KEY_ARTIST, song.displayArtist)
                .putString(KEY_ALBUM, song.displayAlbum)
                .putLong(KEY_ALBUM_ID, song.albumId)
                .putLong(KEY_DURATION, effectiveDur)
                .putLong(KEY_POSITION, effectivePos)
                .putString(KEY_PATH, song.path)
                .putString(KEY_CONTENT_URI, song.contentUriString)
                .putString(KEY_ARTWORK_URI, song.artworkUri)
                .putBoolean(KEY_IS_PLAYING, state.isPlaying)
                .apply()
        } catch (_: Exception) {}
    }

    private fun saveProgressSnapshotOnly(context: Context, positionMs: Long, durationMs: Long) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            if (durationMs > 0L) {
                editor.putLong(KEY_DURATION, durationMs)
            }
            editor.apply()
        } catch (_: Exception) {}
    }

    private fun resolveEffectiveState(context: Context, rawState: PlaybackState): PlaybackState {
        val state = getAuthoritativeState(context, rawState)
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
                val isPlaying = PlaybackManager.activeInstance?.playbackState?.value?.isPlaying
                    ?: prefs.getBoolean(KEY_IS_PLAYING, false)

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
                    positionMs = position,
                    isPlaying = isPlaying
                )
            }
        } catch (_: Exception) {}
        return state
    }

    private fun checkIsFavorite(context: Context, songId: Long): Boolean {
        if (songId <= 0L) return false
        val pmSong = PlaybackManager.activeInstance?.playbackState?.value?.currentSong
        if (pmSong != null && pmSong.id == songId) {
            cachedFavSongId = songId
            cachedIsFavorite = pmSong.isFavorite
            return pmSong.isFavorite
        }
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

    /**
     * 0ms synchronous partial widget update for the Favorite heart button across all 4 widget variants.
     */
    fun setFavoriteStateImmediate(context: Context, songId: Long, isFavorite: Boolean) {
        if (songId <= 0L) return
        cachedFavSongId = songId
        cachedIsFavorite = isFavorite
        try {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val density = WidgetBitmapRenderer.getRenderDensity(appContext)
            val accent = cachedLastAccentColor
            val favBmp30 = WidgetBitmapRenderer.renderFavoriteChipButton(
                sizePx = (30 * density).toInt(),
                isFavorite = isFavorite,
                accentColor = accent
            )
            val favBmp28 = WidgetBitmapRenderer.renderFavoriteChipButton(
                sizePx = (28 * density).toInt(),
                isFavorite = isFavorite,
                accentColor = accent
            )

            val fullIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, FullArtworkMusicWidgetProvider::class.java))
            for (id in fullIds) {
                val views = RemoteViews(appContext.packageName, R.layout.widget_full_artwork)
                views.setImageViewBitmap(R.id.widget_full_btn_favorite, favBmp30)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }

            val smallIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, SmallMusicWidgetProvider::class.java))
            for (id in smallIds) {
                val views = RemoteViews(appContext.packageName, R.layout.widget_small)
                views.setImageViewBitmap(R.id.widget_small_btn_favorite, favBmp30)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }

            val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, MediumMusicWidgetProvider::class.java))
            for (id in mediumIds) {
                val views = RemoteViews(appContext.packageName, R.layout.widget_medium)
                views.setImageViewBitmap(R.id.widget_medium_btn_favorite, favBmp28)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }

            val secondaryIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, SecondaryMusicWidgetProvider::class.java))
            for (id in secondaryIds) {
                val views = RemoteViews(appContext.packageName, R.layout.widget_secondary)
                views.setImageViewBitmap(R.id.widget_secondary_btn_favorite, favBmp28)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }
        } catch (_: Exception) {}
    }

    /**
     * 0ms synchronous partial widget update for the Play/Pause button across all 4 widget variants.
     */
    fun setPlayStateImmediate(context: Context, isPlaying: Boolean) {
        lastRenderedIsPlaying = isPlaying
        try {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val density = WidgetBitmapRenderer.getRenderDensity(appContext)
            val accent = cachedLastAccentColor

            val playBmp38 = WidgetBitmapRenderer.renderCircularButton(
                context = appContext,
                sizePx = (38 * density).toInt(),
                buttonColor = accent,
                iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
                isPlaying = isPlaying
            )
            val playBmp36 = WidgetBitmapRenderer.renderCircularButton(
                context = appContext,
                sizePx = (36 * density).toInt(),
                buttonColor = accent,
                iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
                isPlaying = isPlaying
            )

            val fullIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, FullArtworkMusicWidgetProvider::class.java))
            for (id in fullIds) {
                val views = RemoteViews(appContext.packageName, R.layout.widget_full_artwork)
                views.setImageViewBitmap(R.id.widget_full_btn_play_pause, playBmp38)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }

            val smallIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, SmallMusicWidgetProvider::class.java))
            for (id in smallIds) {
                val views = RemoteViews(appContext.packageName, R.layout.widget_small)
                views.setImageViewBitmap(R.id.widget_small_btn_play_pause, playBmp36)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }

            val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, MediumMusicWidgetProvider::class.java))
            for (id in mediumIds) {
                val minWidthDp = getWidgetMinWidthDp(appWidgetManager, id, 320)
                val playDp = if (minWidthDp < 250) 34 else 38
                val bmp = if (playDp == 38) playBmp38 else WidgetBitmapRenderer.renderCircularButton(
                    context = appContext,
                    sizePx = (playDp * density).toInt(),
                    buttonColor = accent,
                    iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
                    isPlaying = isPlaying
                )
                val views = RemoteViews(appContext.packageName, R.layout.widget_medium)
                views.setImageViewBitmap(R.id.widget_medium_btn_play_pause, bmp)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }

            val secondaryIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, SecondaryMusicWidgetProvider::class.java))
            for (id in secondaryIds) {
                val minWidthDp = getWidgetMinWidthDp(appWidgetManager, id, 320)
                val playDp = if (minWidthDp < 250) 34 else 38
                val bmp = if (playDp == 38) playBmp38 else WidgetBitmapRenderer.renderCircularButton(
                    context = appContext,
                    sizePx = (playDp * density).toInt(),
                    buttonColor = accent,
                    iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
                    isPlaying = isPlaying
                )
                val views = RemoteViews(appContext.packageName, R.layout.widget_secondary)
                views.setImageViewBitmap(R.id.widget_secondary_btn_play_pause, bmp)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }
        } catch (_: Exception) {}
    }

    private suspend fun ensureQueueRestoredIfEmpty(appContext: Context, pm: PlaybackManager): Boolean {
        if (pm.playbackState.value.queue.isNotEmpty()) return true
        return try {
            val db = AppDatabase.getInstance(appContext)
            val allEntities = db.songDao().getAllSongsOnce()
            if (allEntities.isEmpty()) return false
            val allSongs = allEntities.map { with(com.example.data.local.SongEntity::toDomainModel) { it.toDomainModel() } }
            val prefs = UserPreferencesRepository.getInstance(appContext)
            val lastQueueIds: List<Long> = prefs.lastQueueIds.first()
            val lastSongId: Long = prefs.lastSongId.first()
            val lastPos: Long = prefs.lastPosition.first()
            val lastDur: Long = prefs.lastDuration.first()
            val lastIdx: Int = prefs.lastQueueIndex.first()

            val snapPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val snapSongId = snapPrefs.getLong(KEY_SONG_ID, -1L)
            val snapPos = snapPrefs.getLong(KEY_POSITION, 0L)
            val snapDur = snapPrefs.getLong(KEY_DURATION, 0L)

            val songById = allSongs.associateBy { it.id }
            val mappedQueue: List<com.example.data.model.Song> = lastQueueIds.mapNotNull { id: Long -> songById[id] }
            val restoredQueue: List<com.example.data.model.Song> = if (mappedQueue.isNotEmpty()) mappedQueue else allSongs
            val targetSongId = if (lastSongId > 0L) lastSongId else snapSongId
            val foundIdx = restoredQueue.indexOfFirst { song -> song.id == targetSongId }
            val targetIdx = if (foundIdx >= 0) foundIdx else lastIdx.coerceIn(restoredQueue.indices)
            val targetPos = if (lastPos > 0L) lastPos else snapPos
            val targetDur = if (lastDur > 0L) lastDur else snapDur

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (pm.playbackState.value.queue.isEmpty()) {
                    pm.restorePlaybackState(restoredQueue, targetIdx, targetPos, targetDur)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun launchAppIfClosed(appContext: Context, widgetAction: String) {
        if (!MainActivity.isActivityAlive) {
            try {
                val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_WIDGET_ACTION, widgetAction)
                }
                appContext.startActivity(launchIntent)
            } catch (_: Exception) {}
        }
    }

    fun handleWidgetAction(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext

        val pm = PlaybackManager.activeInstance ?: PlaybackManager.getInstance(appContext)

        when (action) {
            ACTION_WIDGET_PLAY_PAUSE -> {
                if (pm.playbackState.value.queue.isNotEmpty()) {
                    pm.togglePlayPause()
                    updateAllWidgets(appContext, getAuthoritativeState(appContext, pm.playbackState.value))
                } else {
                    setPlayStateImmediate(appContext, true)
                    widgetScope.launch {
                        if (ensureQueueRestoredIfEmpty(appContext, pm)) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                pm.play()
                            }
                            updateAllWidgets(appContext, getAuthoritativeState(appContext, pm.playbackState.value))
                        }
                    }
                }
            }
            ACTION_WIDGET_NEXT -> {
                if (pm.playbackState.value.queue.isNotEmpty()) {
                    pm.skipToNext()
                    updateAllWidgets(appContext, getAuthoritativeState(appContext, pm.playbackState.value))
                } else {
                    widgetScope.launch {
                        if (ensureQueueRestoredIfEmpty(appContext, pm)) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                pm.skipToNext()
                                pm.play()
                            }
                            updateAllWidgets(appContext, getAuthoritativeState(appContext, pm.playbackState.value))
                        }
                    }
                }
            }
            ACTION_WIDGET_PREV -> {
                if (pm.playbackState.value.queue.isNotEmpty()) {
                    pm.skipToPrevious()
                    updateAllWidgets(appContext, getAuthoritativeState(appContext, pm.playbackState.value))
                } else {
                    widgetScope.launch {
                        if (ensureQueueRestoredIfEmpty(appContext, pm)) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                pm.skipToPrevious()
                                pm.play()
                            }
                            updateAllWidgets(appContext, getAuthoritativeState(appContext, pm.playbackState.value))
                        }
                    }
                }
            }
            ACTION_WIDGET_FAVORITE -> {
                val liveSongId = pm.playbackState.value.currentSong?.id
                val snapshotSongId = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getLong(KEY_SONG_ID, -1L)
                val targetSongId = if (liveSongId != null && liveSongId > 0L) liveSongId else snapshotSongId
                if (targetSongId > 0L) {
                    toggleFavoriteSong(appContext, targetSongId)
                }
            }
            ACTION_WIDGET_SEEK -> {
                val fraction = intent.getFloatExtra(EXTRA_SEEK_FRACTION, -1f)
                if (fraction in 0f..1f) {
                    val authState = resolveEffectiveState(appContext, getAuthoritativeState(appContext, pm.playbackState.value))
                    val dur = if (authState.durationMs > 0L) authState.durationMs else (authState.currentSong?.durationMs ?: 0L)
                    if (dur > 0L) {
                        val targetPos = (dur * fraction).toLong().coerceIn(0L, dur)
                        pm.seekTo(targetPos)
                        saveProgressSnapshotOnly(appContext, targetPos, dur)
                        val updated = authState.copy(positionMs = targetPos, durationMs = dur)
                        updateAllWidgets(appContext, updated)
                    }
                }
            }
        }
    }

    fun toggleFavoriteSong(context: Context, songId: Long) {
        if (songId <= 0L) return
        val appContext = context.applicationContext
        val pm = PlaybackManager.activeInstance
        val currentSong = pm?.playbackState?.value?.currentSong
        val optimisticNewFav = when {
            currentSong != null && currentSong.id == songId -> !currentSong.isFavorite
            songId == cachedFavSongId -> !cachedIsFavorite
            else -> true
        }

        // 1. Update widget heart icon in 0ms synchronously on the caller thread!
        setFavoriteStateImmediate(appContext, songId, optimisticNewFav)
        if (pm != null) {
            pm.syncFavoriteStateInMemory(songId, optimisticNewFav)
        }

        // 2. Persist in Room DB and verify full widget state asynchronously
        widgetScope.launch {
            updateMutex.withLock {
                try {
                    val db = AppDatabase.getInstance(appContext)
                    val dbSong = db.songDao().getSongById(songId)
                    val newFav = if (currentSong != null && currentSong.id == songId) {
                        optimisticNewFav
                    } else if (dbSong != null) {
                        !dbSong.isFavorite
                    } else {
                        optimisticNewFav
                    }
                    if (dbSong != null) {
                        db.songDao().setFavoriteBySong(songId, dbSong.path, newFav)
                    } else {
                        db.songDao().setFavorite(songId, newFav)
                    }
                    cachedFavSongId = songId
                    cachedIsFavorite = newFav

                    if (newFav != optimisticNewFav) {
                        setFavoriteStateImmediate(appContext, songId, newFav)
                    }
                    if (pm != null) {
                        pm.syncFavoriteStateInMemory(songId, newFav)
                    }

                    val authState = getAuthoritativeState(appContext, pm?.playbackState?.value)
                    performFullWidgetUpdateInternal(appContext, authState)
                } catch (_: Exception) {}
            }
        }
    }

    fun updateAllWidgets(context: Context, state: PlaybackState) {
        val appContext = context.applicationContext
        val authState = getAuthoritativeState(appContext, state)
        widgetScope.launch {
            updateMutex.withLock {
                performFullWidgetUpdateInternal(appContext, authState)
            }
        }
    }

    private fun performFullWidgetUpdateInternal(context: Context, rawState: PlaybackState) {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val fullIds = appWidgetManager.getAppWidgetIds(ComponentName(context, FullArtworkMusicWidgetProvider::class.java))
            val smallIds = appWidgetManager.getAppWidgetIds(ComponentName(context, SmallMusicWidgetProvider::class.java))
            val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MediumMusicWidgetProvider::class.java))
            val secondaryIds = appWidgetManager.getAppWidgetIds(ComponentName(context, SecondaryMusicWidgetProvider::class.java))

            val state = resolveEffectiveState(context, rawState)
            val song = state.currentSong

            if (fullIds.isEmpty() && smallIds.isEmpty() && mediumIds.isEmpty() && secondaryIds.isEmpty()) {
                lastRenderedTrackIdentity = song?.let { com.example.ui.components.ArtworkResolutionCache.trackIdentity(it.id, it.path) }.orEmpty()
                lastRenderedIsPlaying = state.isPlaying
                lastRenderedHasArtwork = song != null && com.example.util.ArtworkManager.getCachedNotificationBitmap(song.id, song.path) != null
                return
            }

            refreshThemePrefsIfNeeded(context)

            val sourceBitmap = WidgetBitmapRenderer.getOrLoadSongBitmap(context, song)
            val themeColors = WidgetBitmapRenderer.getThemeColors(
                songBitmap = sourceBitmap,
                isDynamicTheme = cachedIsDynamicTheme,
                manualAccentColor = cachedManualAccentArgb
            )
            cachedLastAccentColor = themeColors.accentColor
            val isFavorite = if (song != null) checkIsFavorite(context, song.id) else false

            lastRenderedTrackIdentity = song?.let { com.example.ui.components.ArtworkResolutionCache.trackIdentity(it.id, it.path) }.orEmpty()
            lastRenderedIsPlaying = state.isPlaying
            lastRenderedHasArtwork = sourceBitmap != null && !sourceBitmap.isRecycled

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
        } catch (_: Exception) {}
    }

    fun updateWidgetsProgress(context: Context, positionMs: Long, durationMs: Long) {
        progressUpdates.trySend(WidgetProgress(context.applicationContext, positionMs, durationMs))
    }

    fun updateWidgetsProgress(context: Context, state: PlaybackState) {
        updateWidgetProgressOnly(context, state)
    }

    fun updateWidgetProgressOnly(context: Context, state: PlaybackState) {
        val appContext = context.applicationContext
        widgetScope.launch {
            updateMutex.withLock {
                performProgressOnlyWidgetUpdateInternal(appContext, state)
            }
        }
    }

    private fun performProgressOnlyWidgetUpdateInternal(context: Context, rawState: PlaybackState) {
        try {
            val rawSong = rawState.currentSong
            val rawTrackIdentity = rawSong?.let { com.example.ui.components.ArtworkResolutionCache.trackIdentity(it.id, it.path) }.orEmpty()
            val hasNewlyDecodedArt = !lastRenderedHasArtwork && rawSong != null &&
                com.example.util.ArtworkManager.getCachedNotificationBitmap(rawSong.id, rawSong.path) != null

            // If track identity, play/pause state, or decoded artwork state changed since the last full render,
            // automatically promote this progress tick to a full update across all 4 widget variants!
            if (rawTrackIdentity != lastRenderedTrackIdentity || rawState.isPlaying != lastRenderedIsPlaying || hasNewlyDecodedArt) {
                performFullWidgetUpdateInternal(context, rawState)
                return
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MediumMusicWidgetProvider::class.java))
            val secondaryIds = appWidgetManager.getAppWidgetIds(ComponentName(context, SecondaryMusicWidgetProvider::class.java))

            if (mediumIds.isEmpty() && secondaryIds.isEmpty()) {
                return
            }

            val state = resolveEffectiveState(context, rawState)
            val song = state.currentSong
            val durationMs = if (state.durationMs > 0L) state.durationMs else (song?.durationMs ?: 0L)
            val posMs = state.positionMs.coerceAtLeast(0L)
            val progressRatio = if (durationMs > 0L) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

            val currentText = formatTime(posMs)
            val totalText = formatTime(durationMs)

            val accentColor = cachedLastAccentColor
            val density = WidgetBitmapRenderer.getRenderDensity(context)

            if (mediumIds.isNotEmpty()) {
                val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
                    context = context,
                    widthPx = (220 * density).toInt(),
                    heightPx = (16 * density).toInt(),
                    progressRatio = progressRatio,
                    accentColor = accentColor
                )
                for (id in mediumIds) {
                    try {
                        val views = RemoteViews(context.packageName, R.layout.widget_medium)
                        views.setTextViewText(R.id.widget_medium_time_current, currentText)
                        views.setTextViewText(R.id.widget_medium_time_total, totalText)
                        views.setImageViewBitmap(R.id.widget_medium_progress, progBmp)
                        appWidgetManager.partiallyUpdateAppWidget(id, views)
                    } catch (_: Exception) {}
                }
            }

            if (secondaryIds.isNotEmpty()) {
                val progressBmp = WidgetBitmapRenderer.renderStraightProgressBar(
                    context = context,
                    widthPx = (220 * density).toInt(),
                    heightPx = (15 * density).toInt(),
                    progressRatio = progressRatio,
                    accentColor = accentColor
                )
                for (id in secondaryIds) {
                    try {
                        val views = RemoteViews(context.packageName, R.layout.widget_secondary)
                        views.setTextViewText(R.id.widget_secondary_time_current, currentText)
                        views.setTextViewText(R.id.widget_secondary_time_total, totalText)
                        views.setImageViewBitmap(R.id.widget_secondary_progress, progressBmp)
                        appWidgetManager.partiallyUpdateAppWidget(id, views)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun getWidgetMinWidthDp(
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
        defaultWidthDp: Int
    ): Int {
        return try {
            val options: Bundle? = appWidgetManager.getAppWidgetOptions(widgetId)
            val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            if (minW > 40) minW else defaultWidthDp
        } catch (_: Exception) {
            defaultWidthDp
        }
    }

    private fun resolveWidgetDimensionsPx(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
        defaultWidthDp: Int,
        defaultHeightDp: Int,
        maxWidthDp: Int,
        maxHeightDp: Int
    ): Pair<Int, Int> {
        val density = WidgetBitmapRenderer.getRenderDensity(context)
        return try {
            val options: Bundle? = appWidgetManager.getAppWidgetOptions(widgetId)
            val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            val wDp = (if (minW > 40) minW else defaultWidthDp).coerceIn(110, maxWidthDp)
            val hDp = (if (minH > 40) minH else defaultHeightDp).coerceIn(100, maxHeightDp)
            Pair((wDp * density).toInt().coerceAtLeast(100), (hDp * density).toInt().coerceAtLeast(100))
        } catch (_: Exception) {
            Pair((defaultWidthDp * density).toInt(), (defaultHeightDp * density).toInt())
        }
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

        val density = WidgetBitmapRenderer.getRenderDensity(context)
        val favBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = (30 * density).toInt(),
            isFavorite = isFavorite,
            accentColor = themeColors.accentColor
        )
        val prevBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (32 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PREV
        )
        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (38 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying
        )
        val nextBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (32 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.NEXT
        )

        val openNowPlayingIntent = createOpenNowPlayingIntent(context, 10)
        for (widgetId in appWidgetIds) {
            try {
                val (wPx, hPx) = resolveWidgetDimensionsPx(context, appWidgetManager, widgetId, 160, 160, 200, 200)
                val bgBmp = WidgetBitmapRenderer.renderFullBleedArtworkBackground(
                    context = context,
                    source = sourceBitmap,
                    widthPx = wPx,
                    heightPx = hPx,
                    cornerRadiusDp = 22f,
                    accentColor = themeColors.accentColor
                )
                val views = RemoteViews(context.packageName, R.layout.widget_full_artwork)
                views.setImageViewBitmap(R.id.widget_full_bg, bgBmp)
                views.setImageViewBitmap(R.id.widget_full_btn_favorite, favBmp)
                views.setTextViewText(R.id.widget_full_title, title)
                views.setTextViewText(R.id.widget_full_subtitle, subtitle)
                views.setImageViewBitmap(R.id.widget_full_btn_prev, prevBmp)
                views.setImageViewBitmap(R.id.widget_full_btn_play_pause, playPauseBmp)
                views.setImageViewBitmap(R.id.widget_full_btn_next, nextBmp)

                views.setOnClickPendingIntent(R.id.widget_full_root, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_full_btn_favorite, createActionIntent(context, FullArtworkMusicWidgetProvider::class.java, ACTION_WIDGET_FAVORITE, 100))
                views.setOnClickPendingIntent(R.id.widget_full_btn_prev, createActionIntent(context, FullArtworkMusicWidgetProvider::class.java, ACTION_WIDGET_PREV, 101))
                views.setOnClickPendingIntent(R.id.widget_full_btn_play_pause, createActionIntent(context, FullArtworkMusicWidgetProvider::class.java, ACTION_WIDGET_PLAY_PAUSE, 102))
                views.setOnClickPendingIntent(R.id.widget_full_btn_next, createActionIntent(context, FullArtworkMusicWidgetProvider::class.java, ACTION_WIDGET_NEXT, 103))

                appWidgetManager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {}
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

        val density = WidgetBitmapRenderer.getRenderDensity(context)
        val artBmp = WidgetBitmapRenderer.renderRoundedArtwork(
            source = sourceBitmap,
            widthPx = (40 * density).toInt(),
            heightPx = (40 * density).toInt(),
            cornerRadiusDp = 12f,
            context = context
        )
        val favBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = (30 * density).toInt(),
            isFavorite = isFavorite,
            accentColor = themeColors.accentColor
        )
        val prevBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (30 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PREV
        )
        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (36 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying
        )
        val nextBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (30 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.NEXT
        )

        val openNowPlayingIntent = createOpenNowPlayingIntent(context, 20)
        for (widgetId in appWidgetIds) {
            try {
                val (wPx, hPx) = resolveWidgetDimensionsPx(context, appWidgetManager, widgetId, 160, 160, 200, 200)
                val bgBmp = WidgetBitmapRenderer.renderCardBackground(
                    context = context,
                    widthPx = wPx,
                    heightPx = hPx,
                    cornerRadiusDp = 22f,
                    bgColor = themeColors.containerBgColor,
                    borderColor = themeColors.containerBorderColor
                )
                val views = RemoteViews(context.packageName, R.layout.widget_small)
                views.setImageViewBitmap(R.id.widget_small_bg, bgBmp)
                views.setImageViewBitmap(R.id.widget_small_artwork, artBmp)
                views.setImageViewBitmap(R.id.widget_small_btn_favorite, favBmp)
                views.setTextViewText(R.id.widget_small_title, title)
                views.setTextViewText(R.id.widget_small_subtitle, subtitle)
                views.setImageViewBitmap(R.id.widget_small_btn_prev, prevBmp)
                views.setImageViewBitmap(R.id.widget_small_btn_play_pause, playPauseBmp)
                views.setImageViewBitmap(R.id.widget_small_btn_next, nextBmp)

                views.setOnClickPendingIntent(R.id.widget_small_root, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_small_artwork, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_small_btn_favorite, createActionIntent(context, SmallMusicWidgetProvider::class.java, ACTION_WIDGET_FAVORITE, 200))
                views.setOnClickPendingIntent(R.id.widget_small_btn_prev, createActionIntent(context, SmallMusicWidgetProvider::class.java, ACTION_WIDGET_PREV, 201))
                views.setOnClickPendingIntent(R.id.widget_small_btn_play_pause, createActionIntent(context, SmallMusicWidgetProvider::class.java, ACTION_WIDGET_PLAY_PAUSE, 202))
                views.setOnClickPendingIntent(R.id.widget_small_btn_next, createActionIntent(context, SmallMusicWidgetProvider::class.java, ACTION_WIDGET_NEXT, 203))

                appWidgetManager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {}
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

        val density = WidgetBitmapRenderer.getRenderDensity(context)
        val favBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = (28 * density).toInt(),
            isFavorite = isFavorite,
            accentColor = themeColors.accentColor
        )
        val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
            context = context,
            widthPx = (220 * density).toInt(),
            heightPx = (15 * density).toInt(),
            progressRatio = progressRatio,
            accentColor = themeColors.accentColor
        )

        val openNowPlayingIntent = createOpenNowPlayingIntent(context, 30)
        for (widgetId in appWidgetIds) {
            try {
                val minWidthDp = getWidgetMinWidthDp(appWidgetManager, widgetId, 320)
                // Adapt artwork and control button sizes when user resizes widget horizontally
                // so Previous and Next track buttons never clip or half-vanish!
                val artSizeDp = when {
                    minWidthDp < 215 -> 60
                    minWidthDp < 255 -> 72
                    minWidthDp < 290 -> 82
                    else -> 92
                }
                val sideBtnDp = if (minWidthDp < 250) 28 else 32
                val playBtnDp = if (minWidthDp < 250) 34 else 38

                val artBmp = WidgetBitmapRenderer.renderRoundedArtwork(
                    source = sourceBitmap,
                    widthPx = (artSizeDp * density).toInt(),
                    heightPx = (artSizeDp * density).toInt(),
                    cornerRadiusDp = 15f,
                    context = context
                )
                val prevBmp = WidgetBitmapRenderer.renderCircularButton(
                    context = context,
                    sizePx = (sideBtnDp * density).toInt(),
                    buttonColor = themeColors.accentColor,
                    iconType = WidgetBitmapRenderer.ButtonIconType.PREV
                )
                val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
                    context = context,
                    sizePx = (playBtnDp * density).toInt(),
                    buttonColor = themeColors.accentColor,
                    iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
                    isPlaying = state.isPlaying
                )
                val nextBmp = WidgetBitmapRenderer.renderCircularButton(
                    context = context,
                    sizePx = (sideBtnDp * density).toInt(),
                    buttonColor = themeColors.accentColor,
                    iconType = WidgetBitmapRenderer.ButtonIconType.NEXT
                )

                val (wPx, hPx) = resolveWidgetDimensionsPx(context, appWidgetManager, widgetId, 320, 130, 380, 160)
                val bgBmp = WidgetBitmapRenderer.renderCardBackground(
                    context = context,
                    widthPx = wPx,
                    heightPx = hPx,
                    cornerRadiusDp = 22f,
                    bgColor = themeColors.containerBgColor,
                    borderColor = themeColors.containerBorderColor
                )
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

                views.setOnClickPendingIntent(R.id.widget_medium_root, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_medium_artwork, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_medium_header_click, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_medium_progress, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_medium_btn_favorite, createActionIntent(context, MediumMusicWidgetProvider::class.java, ACTION_WIDGET_FAVORITE, 300))
                views.setOnClickPendingIntent(R.id.widget_medium_btn_prev, createActionIntent(context, MediumMusicWidgetProvider::class.java, ACTION_WIDGET_PREV, 301))
                views.setOnClickPendingIntent(R.id.widget_medium_btn_play_pause, createActionIntent(context, MediumMusicWidgetProvider::class.java, ACTION_WIDGET_PLAY_PAUSE, 302))
                views.setOnClickPendingIntent(R.id.widget_medium_btn_next, createActionIntent(context, MediumMusicWidgetProvider::class.java, ACTION_WIDGET_NEXT, 303))

                appWidgetManager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {}
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

        val density = WidgetBitmapRenderer.getRenderDensity(context)
        val favBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = (28 * density).toInt(),
            isFavorite = isFavorite,
            accentColor = themeColors.accentColor
        )
        val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
            context = context,
            widthPx = (220 * density).toInt(),
            heightPx = (15 * density).toInt(),
            progressRatio = progressRatio,
            accentColor = themeColors.accentColor
        )

        val openNowPlayingIntent = createOpenNowPlayingIntent(context, 40)
        for (widgetId in appWidgetIds) {
            try {
                val minWidthDp = getWidgetMinWidthDp(appWidgetManager, widgetId, 320)
                val artWidthDp = when {
                    minWidthDp < 215 -> 72
                    minWidthDp < 255 -> 86
                    minWidthDp < 290 -> 98
                    else -> 112
                }
                val sideBtnDp = if (minWidthDp < 250) 28 else 32
                val playBtnDp = if (minWidthDp < 250) 34 else 38

                val prevBmp = WidgetBitmapRenderer.renderCircularButton(
                    context = context,
                    sizePx = (sideBtnDp * density).toInt(),
                    buttonColor = themeColors.accentColor,
                    iconType = WidgetBitmapRenderer.ButtonIconType.PREV
                )
                val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
                    context = context,
                    sizePx = (playBtnDp * density).toInt(),
                    buttonColor = themeColors.accentColor,
                    iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
                    isPlaying = state.isPlaying
                )
                val nextBmp = WidgetBitmapRenderer.renderCircularButton(
                    context = context,
                    sizePx = (sideBtnDp * density).toInt(),
                    buttonColor = themeColors.accentColor,
                    iconType = WidgetBitmapRenderer.ButtonIconType.NEXT
                )

                val (wPx, hPx) = resolveWidgetDimensionsPx(context, appWidgetManager, widgetId, 320, 130, 380, 160)
                val bgBmp = WidgetBitmapRenderer.renderCardBackground(
                    context = context,
                    widthPx = wPx,
                    heightPx = hPx,
                    cornerRadiusDp = 22f,
                    bgColor = themeColors.containerBgColor,
                    borderColor = themeColors.containerBorderColor
                )
                val leftBleedArtBmp = WidgetBitmapRenderer.renderLeftBleedArtwork(
                    source = sourceBitmap,
                    widthPx = (artWidthDp * density).toInt(),
                    heightPx = hPx,
                    leftCornerRadiusDp = 22f,
                    context = context
                )
                val views = RemoteViews(context.packageName, R.layout.widget_secondary)
                views.setImageViewBitmap(R.id.widget_secondary_bg, bgBmp)
                views.setImageViewBitmap(R.id.widget_secondary_artwork, leftBleedArtBmp)
                views.setImageViewBitmap(R.id.widget_secondary_btn_favorite, favBmp)
                views.setTextViewText(R.id.widget_secondary_title, title)
                views.setTextViewText(R.id.widget_secondary_subtitle, subtitle)
                views.setImageViewBitmap(R.id.widget_secondary_progress, progBmp)
                views.setTextViewText(R.id.widget_secondary_time_current, formatTime(posMs))
                views.setTextViewText(R.id.widget_secondary_time_total, formatTime(durationMs))
                views.setImageViewBitmap(R.id.widget_secondary_btn_prev, prevBmp)
                views.setImageViewBitmap(R.id.widget_secondary_btn_play_pause, playPauseBmp)
                views.setImageViewBitmap(R.id.widget_secondary_btn_next, nextBmp)

                views.setOnClickPendingIntent(R.id.widget_secondary_root, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_secondary_artwork, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_secondary_header_click, openNowPlayingIntent)
                setSecondarySeekActions(context, views)
                views.setOnClickPendingIntent(R.id.widget_secondary_btn_favorite, createActionIntent(context, SecondaryMusicWidgetProvider::class.java, ACTION_WIDGET_FAVORITE, 400))
                views.setOnClickPendingIntent(R.id.widget_secondary_btn_prev, createActionIntent(context, SecondaryMusicWidgetProvider::class.java, ACTION_WIDGET_PREV, 401))
                views.setOnClickPendingIntent(R.id.widget_secondary_btn_play_pause, createActionIntent(context, SecondaryMusicWidgetProvider::class.java, ACTION_WIDGET_PLAY_PAUSE, 402))
                views.setOnClickPendingIntent(R.id.widget_secondary_btn_next, createActionIntent(context, SecondaryMusicWidgetProvider::class.java, ACTION_WIDGET_NEXT, 403))

                appWidgetManager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {}
        }
    }

    private fun createOpenNowPlayingIntent(context: Context, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_NOW_PLAYING, true)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
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
        // Playback controls always target the existing AppWidgetProvider/MediaSession path.
        // Only the dedicated surface PendingIntent is allowed to launch MainActivity.
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

    private fun setSecondarySeekActions(context: Context, views: RemoteViews) {
        val zoneIds = intArrayOf(
            R.id.widget_secondary_seek_zone_0,
            R.id.widget_secondary_seek_zone_1,
            R.id.widget_secondary_seek_zone_2,
            R.id.widget_secondary_seek_zone_3,
            R.id.widget_secondary_seek_zone_4,
            R.id.widget_secondary_seek_zone_5,
            R.id.widget_secondary_seek_zone_6,
            R.id.widget_secondary_seek_zone_7,
            R.id.widget_secondary_seek_zone_8,
            R.id.widget_secondary_seek_zone_9
        )
        zoneIds.forEachIndexed { index, viewId ->
            val fraction = (index + 0.5f) / zoneIds.size
            val intent = Intent(context, SecondaryMusicWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_SEEK
                putExtra(EXTRA_SEEK_FRACTION, fraction)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                410 + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(viewId, pendingIntent)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format("%d:%02d", minutes, seconds)
    }
}
