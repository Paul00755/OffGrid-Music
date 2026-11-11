package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
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
    private var cachedIsLightTheme: Boolean = false
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
    @Volatile
    private var lastRenderedIsLightTheme: Boolean = false

    @Volatile
    private var cachedLyricsSongId: Long = -1L
    @Volatile
    private var cachedSyncedLines: List<Pair<Long, String>> = emptyList()
    @Volatile
    private var cachedPlainLines: List<String> = emptyList()
    @Volatile
    private var cachedLyricsOffsetMs: Long = 0L
    @Volatile
    private var lastLyricsDbCheckMs: Long = 0L
    @Volatile
    private var lastLyricsLineSignature: String = ""
    @Volatile
    private var currentLyricsFlipperPage: Int = 0
    @Volatile
    private var lastLyricsTransitionTimeMs: Long = 0L
    @Volatile
    private var lyricsTransitionToken: Long = 0L

    @Volatile
    private var lyricsCacheGeneration: Long = 0L

    private val attemptedLyricsFetchSongIds: MutableSet<Long> =
        java.util.Collections.synchronizedSet(HashSet<Long>())
    private val lyricsFetchCompletedNoLyricsSongIds: MutableSet<Long> =
        java.util.Collections.synchronizedSet(HashSet<Long>())
    private val pendingLyricsDbLoadSongIds: MutableSet<Long> =
        java.util.Collections.synchronizedSet(HashSet<Long>())

    internal enum class LyricsDisplayMode {
        NONE,
        LOADING,
        SYNCED,
        PLAIN,
        MISSING
    }

    internal const val MAX_SURROUNDING_LYRIC_LINES = 6

    internal data class AdaptiveLyricsLayoutSpec(
        val prevCount: Int,
        val nextCount: Int,
        val containerPadPx: Int,
        val linePadVerticalPx: Int,
        val currPadVerticalPx: Int,
        val currTextSizeSp: Float,
        val nearTextSizeSp: Float,
        val midTextSizeSp: Float,
        val farTextSizeSp: Float,
        val keepSymmetricCenter: Boolean = false
    )

    /**
     * Computes how many previous and upcoming lyric lines should be displayed for a given
     * widget width/height in dp, keeping the active line vertically centered in Standard (4x2)
     * and Horizontally Shrunk (3x2/2x2) widgets while preserving Expanded (4x3) and
     * Extra-Expanded (4x4+) layouts untouched.
     */
    internal fun computeAdaptiveLyricsLayout(
        widthDp: Int,
        heightDp: Int,
        availablePrevLines: Int,
        availableNextLines: Int,
        density: Float = 1f
    ): AdaptiveLyricsLayoutSpec {
        // Minimum vertical height for the Lyrics widget is the 2-row standard footprint (130dp).
        val safeH = heightDp.coerceIn(130, 650)
        val safeW = widthDp.coerceIn(140, 650)
        val isStandardTwoRow = safeH < 180
        val isHorizontallyShrunk = safeW < 255

        // Height thresholds calibrated to real Android launcher cell dp reporting
        // (including compact-grid launchers like OplusLauncher/ColorOS, OneUI, and Pixel Launcher):
        // - 2 rows (4x2 Standard & 3x2/2x2 Horizontally Shrunk): 130..179dp (2 prev + 1 curr + 2 next = 5 lines, centered)
        // - 3 rows (4x3 Expanded): 180..197dp (5 prev + 1 curr + 5 next = 11 lines)
        // - 4+ rows (4x4+ Extra-Expanded): >= 198dp (6 prev + 1 curr + 6 next = 13 lines)
        val (basePrev, baseNext) = when {
            isStandardTwoRow -> 2 to 2 // Standard 4x2 & Horizontally Shrunk: 2 prev + current + 2 next (5 lines)
            safeH < 198 -> 5 to 5      // Expanded 4x3: 5 prev + current + 5 next (11 lines)
            else -> 6 to 6             // Extra-Expanded 4x4+: 6 prev + current + 6 next (13 lines)
        }

        val availPrev = availablePrevLines.coerceAtLeast(0)
        val availNext = availableNextLines.coerceAtLeast(0)

        var actualPrev = minOf(basePrev, availPrev)
        var actualNext = minOf(baseNext, availNext)

        if (!isStandardTwoRow) {
            // Smart slot rebalancing for Expanded and Extra-Expanded widgets:
            // if near the beginning or end of a song, allocate unused slots so expanded widgets stay full.
            val unusedPrev = basePrev - actualPrev
            if (unusedPrev > 0 && availNext > actualNext) {
                actualNext = minOf(MAX_SURROUNDING_LYRIC_LINES, minOf(availNext, actualNext + unusedPrev))
            }
            val unusedNext = baseNext - actualNext
            if (unusedNext > 0 && availPrev > actualPrev) {
                actualPrev = minOf(MAX_SURROUNDING_LYRIC_LINES, minOf(availPrev, actualPrev + unusedNext))
            }
        }

        val containerPadDp = when {
            isStandardTwoRow -> 10
            safeH < 198 -> 12
            else -> 14
        }

        val chromeDp = (containerPadDp * 2) + 62
        val rawStageHeightDp = (safeH - chromeDp).coerceAtLeast(32)
        val stageHeightDp = when {
            safeH >= 198 -> maxOf(rawStageHeightDp, 255)
            safeH >= 180 -> maxOf(rawStageHeightDp, 185)
            else -> rawStageHeightDp
        }

        val visibleLineCount = if (isStandardTwoRow) {
            5
        } else {
            (1 + actualPrev + actualNext).coerceAtLeast(1)
        }

        val widthBoostSp = if (safeW >= 340) 0.5f else 0f
        val currSp = when {
            safeH >= 198 -> 16.2f + widthBoostSp
            safeH >= 180 -> 15.8f + widthBoostSp
            isHorizontallyShrunk -> 13.6f
            else -> 14.4f + (widthBoostSp * 0.4f)
        }.coerceIn(13.4f, 17.5f)

        val nearSp = when {
            safeH >= 198 -> (currSp - 2.6f).coerceIn(13.0f, 14.2f)
            safeH >= 180 -> (currSp - 2.5f).coerceIn(12.6f, 13.8f)
            isHorizontallyShrunk -> 11.1f
            else -> 11.6f
        }
        val midSp = when {
            safeH >= 198 -> (currSp - 3.3f).coerceIn(12.2f, 13.4f)
            safeH >= 180 -> (currSp - 3.2f).coerceIn(12.0f, 13.0f)
            isHorizontallyShrunk -> 10.6f
            else -> 11.0f
        }
        val farSp = when {
            safeH >= 198 -> (currSp - 4.0f).coerceIn(11.6f, 12.6f)
            safeH >= 180 -> (currSp - 3.8f).coerceIn(11.4f, 12.4f)
            else -> 10.2f
        }

        val surroundingCount = if (isStandardTwoRow) 4 else (actualPrev + actualNext)
        val estTextHeightDp = (currSp * 1.42f) + (surroundingCount * (midSp * 1.28f))
        val slackDp = (stageHeightDp.toFloat() - estTextHeightDp).coerceAtLeast(0f)

        val perSlotSlackDp = slackDp / (visibleLineCount + 1).toFloat()
        val linePadVerticalDp = when {
            safeH >= 198 -> (perSlotSlackDp * 0.55f).coerceIn(3.0f, 4.8f)
            safeH >= 180 -> (perSlotSlackDp * 0.55f).coerceIn(2.8f, 4.4f)
            isHorizontallyShrunk -> 0.8f
            else -> 1.2f
        }
        val currPadVerticalDp = when {
            safeH >= 198 -> (linePadVerticalDp + 2.2f).coerceIn(5.2f, 7.5f)
            safeH >= 180 -> (linePadVerticalDp + 2.0f).coerceIn(4.8f, 6.8f)
            isHorizontallyShrunk -> 1.6f
            else -> 2.2f
        }

        val safeDensity = density.coerceAtLeast(0.75f)
        return AdaptiveLyricsLayoutSpec(
            prevCount = actualPrev,
            nextCount = actualNext,
            containerPadPx = (containerPadDp * safeDensity).toInt(),
            linePadVerticalPx = (linePadVerticalDp * safeDensity).toInt(),
            currPadVerticalPx = (currPadVerticalDp * safeDensity).toInt(),
            currTextSizeSp = currSp,
            nearTextSizeSp = nearSp,
            midTextSizeSp = midSp,
            farTextSizeSp = farSp,
            keepSymmetricCenter = isStandardTwoRow
        )
    }

    private val cachedWidgetOptions = java.util.concurrent.ConcurrentHashMap<Int, Bundle>()
    private val cachedWidgetOptionsTimestamp = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun onWidgetOptionsChanged(
        context: Context,
        appWidgetId: Int,
        newOptions: Bundle?,
        state: PlaybackState
    ) {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && newOptions != null) {
            cachedWidgetOptions[appWidgetId] = Bundle(newOptions)
            cachedWidgetOptionsTimestamp[appWidgetId] = System.currentTimeMillis()
        }
        lastLyricsLineSignature = ""
        updateAllWidgets(context, state)
    }

    private data class LyricsStageWindow(
        val prevLines: List<String> = emptyList(), // Index 0 = prev1 (nearest), Index 5 = prev6 (farthest)
        val currLine: String,
        val nextLines: List<String> = emptyList(), // Index 0 = next1 (nearest), Index 5 = next6 (farthest)
        val signature: String,
        val songPrefix: String,
        val mode: LyricsDisplayMode,
        val shouldAnimateTransition: Boolean,
        val prevLine: String = prevLines.getOrNull(0) ?: "",
        val prev2Line: String = prevLines.getOrNull(1) ?: "",
        val prev3Line: String = prevLines.getOrNull(2) ?: "",
        val prev4Line: String = prevLines.getOrNull(3) ?: "",
        val prev5Line: String = prevLines.getOrNull(4) ?: "",
        val prev6Line: String = prevLines.getOrNull(5) ?: "",
        val nextLine: String = nextLines.getOrNull(0) ?: "",
        val next2Line: String = nextLines.getOrNull(1) ?: "",
        val next3Line: String = nextLines.getOrNull(2) ?: "",
        val next4Line: String = nextLines.getOrNull(3) ?: "",
        val next5Line: String = nextLines.getOrNull(4) ?: "",
        val next6Line: String = nextLines.getOrNull(5) ?: ""
    )

    fun invalidateLyricsCache(songId: Long = -1L) {
        lyricsCacheGeneration++
        if (songId > 0L) {
            attemptedLyricsFetchSongIds.remove(songId)
            lyricsFetchCompletedNoLyricsSongIds.remove(songId)
            pendingLyricsDbLoadSongIds.remove(songId)
        } else {
            attemptedLyricsFetchSongIds.clear()
            lyricsFetchCompletedNoLyricsSongIds.clear()
            pendingLyricsDbLoadSongIds.clear()
        }
        if (songId <= 0L || songId == cachedLyricsSongId) {
            cachedLyricsSongId = -1L
            cachedSyncedLines = emptyList()
            cachedPlainLines = emptyList()
            lastLyricsDbCheckMs = 0L
            lastLyricsLineSignature = ""
        }
    }

    fun updateLyricsOffset(context: Context, songId: Long, offsetMs: Long) {
        if (songId <= 0L) return
        val appContext = context.applicationContext
        if (songId == cachedLyricsSongId) {
            cachedLyricsOffsetMs = offsetMs
            lastLyricsLineSignature = ""
        } else {
            lastLyricsDbCheckMs = 0L
        }
        val liveState = getAuthoritativeState(appContext, PlaybackManager.activeInstance?.playbackState?.value)
        updateAllWidgets(appContext, liveState)
    }

    fun syncLyricsFromApp(
        context: Context,
        songId: Long,
        lyrics: com.example.data.model.Lyrics?
    ) {
        if (songId <= 0L) return
        lyricsCacheGeneration++
        pendingLyricsDbLoadSongIds.remove(songId)
        attemptedLyricsFetchSongIds.add(songId)

        val synced = lyrics?.parseSyncedLines().orEmpty()
        val plain = if (synced.isEmpty() && !lyrics?.plainLyrics.isNullOrBlank()) {
            lyrics!!.plainLyrics!!
                .lines()
                .map { it.replace(Regex("""\[\d{2}:\d{2}(?:\.\d{1,3})?\]"""), "").trim() }
                .filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        cachedSyncedLines = synced
        cachedPlainLines = plain
        cachedLyricsSongId = songId
        lastLyricsDbCheckMs = System.currentTimeMillis()
        lastLyricsLineSignature = ""

        if (synced.isEmpty() && plain.isEmpty()) {
            lyricsFetchCompletedNoLyricsSongIds.add(songId)
        } else {
            lyricsFetchCompletedNoLyricsSongIds.remove(songId)
        }

        val appContext = context.applicationContext
        cachedLyricsOffsetMs = try {
            UserPreferencesRepository.getInstance(appContext).getLyricsSyncOffsetMs(songId)
        } catch (_: Exception) {
            0L
        }
        val liveState = getAuthoritativeState(appContext, PlaybackManager.activeInstance?.playbackState?.value)
        updateAllWidgets(appContext, liveState)
    }

    private fun computeTrackRenderSignature(song: com.example.data.model.Song?): String {
        if (song == null) return ""
        val baseId = com.example.ui.components.ArtworkResolutionCache.trackIdentity(song.id, song.path)
        val bmpHash = System.identityHashCode(
            com.example.util.ArtworkManager.getCachedNotificationBitmap(song.id, song.path)
        )
        return "$baseId|${song.displayTitle}|${song.displayArtist}|${song.displayAlbum}|${song.artworkUri.orEmpty()}|$bmpHash"
    }

    private fun refreshThemePrefsIfNeeded(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastThemeCheckMs < 1500L) return
        lastThemeCheckMs = now
        try {
            val prefs = UserPreferencesRepository.getInstance(context.applicationContext)
            cachedIsDynamicTheme = prefs.getDynamicArtworkThemeSync()
            val rawMode = prefs.getThemeModeSync().uppercase()
            val rawUiTheme = prefs.getUiThemeSync().uppercase()
            cachedIsLightTheme = when {
                rawMode == "LIGHT" -> true
                rawMode == "DARK" || rawMode == "AMOLED" -> false
                rawUiTheme in setOf("SOLAR_DAWN", "NORDIC_FROST", "ROSE_QUARTZ", "MINT_BREEZE", "LAVENDER_MIST", "WARM_SAND") -> true
                else -> {
                    val nightMask = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    nightMask != android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
            }
            val accentPreset = prefs.getAccentColorPresetSync().uppercase()
            cachedManualAccentArgb = when (accentPreset) {
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
        lastLyricsLineSignature = ""
        WidgetBitmapRenderer.clearCache()
        updateAllWidgets(context, state)
    }

    fun forceRefreshMetadata(context: Context, state: PlaybackState) {
        val appContext = context.applicationContext
        val songId = state.currentSong?.id ?: -1L
        if (songId > 0L) {
            WidgetBitmapRenderer.invalidateSong(songId)
            invalidateLyricsCache(songId)
        } else {
            WidgetBitmapRenderer.clearCache()
        }
        lastRenderedTrackIdentity = ""
        savePlaybackSnapshot(appContext, state)
        updateAllWidgets(appContext, state)
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

            val lyricsIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, LyricsMusicWidgetProvider::class.java))
            if (lyricsIds.isNotEmpty()) {
                val favBmp26 = WidgetBitmapRenderer.renderFavoriteChipButton(
                    sizePx = (26 * density).toInt(),
                    isFavorite = isFavorite,
                    accentColor = accent
                )
                for (id in lyricsIds) {
                    val views = RemoteViews(appContext.packageName, R.layout.widget_lyrics)
                    views.setImageViewBitmap(R.id.widget_lyrics_btn_favorite, favBmp26)
                    appWidgetManager.partiallyUpdateAppWidget(id, views)
                }
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

            val lyricsIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, LyricsMusicWidgetProvider::class.java))
            if (lyricsIds.isNotEmpty()) {
                val playBmp30 = WidgetBitmapRenderer.renderCircularButton(
                    context = appContext,
                    sizePx = (30 * density).toInt(),
                    buttonColor = accent,
                    iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
                    isPlaying = isPlaying
                )
                for (id in lyricsIds) {
                    val views = RemoteViews(appContext.packageName, R.layout.widget_lyrics)
                    views.setImageViewBitmap(R.id.widget_lyrics_btn_play_pause, playBmp30)
                    appWidgetManager.partiallyUpdateAppWidget(id, views)
                }
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
            val lyricsIds = appWidgetManager.getAppWidgetIds(ComponentName(context, LyricsMusicWidgetProvider::class.java))

            val state = resolveEffectiveState(context, rawState)
            val song = state.currentSong

            if (fullIds.isEmpty() && smallIds.isEmpty() && mediumIds.isEmpty() && secondaryIds.isEmpty() && lyricsIds.isEmpty()) {
                lastRenderedTrackIdentity = computeTrackRenderSignature(song)
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

            lastRenderedTrackIdentity = computeTrackRenderSignature(song)
            lastRenderedIsPlaying = state.isPlaying
            lastRenderedHasArtwork = sourceBitmap != null && !sourceBitmap.isRecycled
            lastRenderedIsLightTheme = cachedIsLightTheme

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
            if (lyricsIds.isNotEmpty()) {
                updateLyricsWidgets(context, appWidgetManager, lyricsIds, state, sourceBitmap, themeColors, isFavorite)
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
            refreshThemePrefsIfNeeded(context)
            val rawSong = rawState.currentSong
            val rawTrackIdentity = computeTrackRenderSignature(rawSong)
            val hasNewlyDecodedArt = !lastRenderedHasArtwork && rawSong != null &&
                com.example.util.ArtworkManager.getCachedNotificationBitmap(rawSong.id, rawSong.path) != null

            // If track identity/metadata, play/pause state, theme mode, or decoded artwork state changed since the last full render,
            // automatically promote this progress tick to a full update across all 5 widget variants!
            if (rawTrackIdentity != lastRenderedTrackIdentity ||
                rawState.isPlaying != lastRenderedIsPlaying ||
                cachedIsLightTheme != lastRenderedIsLightTheme ||
                hasNewlyDecodedArt
            ) {
                performFullWidgetUpdateInternal(context, rawState)
                return
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MediumMusicWidgetProvider::class.java))
            val secondaryIds = appWidgetManager.getAppWidgetIds(ComponentName(context, SecondaryMusicWidgetProvider::class.java))
            val lyricsIds = appWidgetManager.getAppWidgetIds(ComponentName(context, LyricsMusicWidgetProvider::class.java))

            if (mediumIds.isEmpty() && secondaryIds.isEmpty() && lyricsIds.isEmpty()) {
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

            if (lyricsIds.isNotEmpty()) {
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                // Do not interrupt an active 260ms ViewFlipper lyric transition with a progress partial update
                if (nowElapsed - lastLyricsTransitionTimeMs < 310L) {
                    return
                }
                val lyricsProgBmp = WidgetBitmapRenderer.renderStraightProgressBar(
                    context = context,
                    widthPx = (220 * density).toInt(),
                    heightPx = (12 * density).toInt(),
                    progressRatio = progressRatio,
                    accentColor = accentColor
                )
                val window = resolveLyricsWindow(context, song, posMs, durationMs)
                val prevSig = lastLyricsLineSignature
                val lineChanged = window.signature != prevSig
                val shouldAnimate = lineChanged &&
                    window.shouldAnimateTransition &&
                    prevSig.isNotEmpty() &&
                    window.songPrefix.isNotEmpty() &&
                    prevSig.startsWith(window.songPrefix)

                lastLyricsLineSignature = window.signature

                val isLight = cachedIsLightTheme
                val currLyricColor = if (isLight) android.graphics.Color.parseColor("#0F172A") else android.graphics.Color.WHITE
                val nearLyricColor = if (isLight) android.graphics.Color.parseColor("#475569") else android.graphics.Color.parseColor("#94A3B8")
                val midLyricColor = if (isLight) android.graphics.Color.parseColor("#64748B") else android.graphics.Color.parseColor("#6B7487")
                val farLyricColor = if (isLight) android.graphics.Color.parseColor("#94A3B8") else android.graphics.Color.parseColor("#4F5768")

                if (shouldAnimate) {
                    currentLyricsFlipperPage = (currentLyricsFlipperPage + 1) % 2
                    lastLyricsTransitionTimeMs = nowElapsed
                    val transitionToken = ++lyricsTransitionToken

                    for (id in lyricsIds) {
                        try {
                            val (wPx, hPx) = resolveWidgetDimensionsPx(context, appWidgetManager, id, 320, 145, 600, 650)
                            val wDp = (wPx / density).toInt()
                            val hDp = (hPx / density).toInt().coerceAtLeast(130)
                            val layoutSpec = computeAdaptiveLyricsLayout(
                                widthDp = wDp,
                                heightDp = hDp,
                                availablePrevLines = window.prevLines.size,
                                availableNextLines = window.nextLines.size,
                                density = density
                            )

                            val views = RemoteViews(context.packageName, R.layout.widget_lyrics)
                            views.setTextViewText(R.id.widget_lyrics_time_current, currentText)
                            views.setTextViewText(R.id.widget_lyrics_time_total, totalText)
                            views.setImageViewBitmap(R.id.widget_lyrics_progress, lyricsProgBmp)
                            bindLyricsFlipperPage(
                                views = views,
                                pageIndex = currentLyricsFlipperPage,
                                window = window,
                                spec = layoutSpec,
                                currColor = currLyricColor,
                                nearColor = nearLyricColor,
                                midColor = midLyricColor,
                                farColor = farLyricColor
                            )
                            views.setDisplayedChild(R.id.widget_lyrics_flipper, currentLyricsFlipperPage)
                            appWidgetManager.partiallyUpdateAppWidget(id, views)
                        } catch (_: Exception) {}
                    }

                    // CRITICAL ANTI-FLICKER CLEANUP:
                    // Android's AppWidgetServiceImpl merges partial RemoteViews into the stored widget.views.
                    // If `setDisplayedChild` remains inside widget.views, every subsequent 1-second
                    // `partiallyUpdateAppWidget` call re-executes `ViewAnimator.setDisplayedChild`, which
                    // unconditionally restarts `mInAnimation` and causes the lyrics to flicker every second!
                    // Once the 260ms slide transition completes (at 300ms), we replace widget.views via
                    // full `updateAppWidget` (which does NOT contain `setDisplayedChild` and syncs both pages).
                    val appContext = context.applicationContext
                    widgetScope.launch {
                        kotlinx.coroutines.delay(300L)
                        updateMutex.withLock {
                            if (transitionToken == lyricsTransitionToken) {
                                try {
                                    val liveState = resolveEffectiveState(appContext, getAuthoritativeState(appContext))
                                    val liveSong = liveState.currentSong
                                    val srcBmp = WidgetBitmapRenderer.getOrLoadSongBitmap(appContext, liveSong)
                                    val tColors = WidgetBitmapRenderer.getThemeColors(
                                        songBitmap = srcBmp,
                                        isDynamicTheme = cachedIsDynamicTheme,
                                        manualAccentColor = cachedManualAccentArgb
                                    )
                                    val isFav = if (liveSong != null) checkIsFavorite(appContext, liveSong.id) else false
                                    val awm = AppWidgetManager.getInstance(appContext)
                                    val currentIds = awm.getAppWidgetIds(ComponentName(appContext, LyricsMusicWidgetProvider::class.java))
                                    if (currentIds.isNotEmpty()) {
                                        updateLyricsWidgets(appContext, awm, currentIds, liveState, srcBmp, tColors, isFav)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } else {
                    for (id in lyricsIds) {
                        try {
                            val (wPx, hPx) = resolveWidgetDimensionsPx(context, appWidgetManager, id, 320, 145, 600, 650)
                            val wDp = (wPx / density).toInt()
                            val hDp = (hPx / density).toInt().coerceAtLeast(130)
                            val layoutSpec = computeAdaptiveLyricsLayout(
                                widthDp = wDp,
                                heightDp = hDp,
                                availablePrevLines = window.prevLines.size,
                                availableNextLines = window.nextLines.size,
                                density = density
                            )

                            val views = RemoteViews(context.packageName, R.layout.widget_lyrics)
                            views.setTextViewText(R.id.widget_lyrics_time_current, currentText)
                            views.setTextViewText(R.id.widget_lyrics_time_total, totalText)
                            views.setImageViewBitmap(R.id.widget_lyrics_progress, lyricsProgBmp)
                            if (lineChanged) {
                                bindLyricsFlipperPage(
                                    views = views,
                                    pageIndex = 0,
                                    window = window,
                                    spec = layoutSpec,
                                    currColor = currLyricColor,
                                    nearColor = nearLyricColor,
                                    midColor = midLyricColor,
                                    farColor = farLyricColor
                                )
                                bindLyricsFlipperPage(
                                    views = views,
                                    pageIndex = 1,
                                    window = window,
                                    spec = layoutSpec,
                                    currColor = currLyricColor,
                                    nearColor = nearLyricColor,
                                    midColor = midLyricColor,
                                    farColor = farLyricColor
                                )
                            }
                            appWidgetManager.partiallyUpdateAppWidget(id, views)
                        } catch (_: Exception) {}
                    }
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
            val options: Bundle? = cachedWidgetOptions[widgetId] ?: appWidgetManager.getAppWidgetOptions(widgetId)
            val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            if (minW > 40) minW else defaultWidthDp
        } catch (_: Exception) {
            defaultWidthDp
        }
    }

    private fun extractOptionsDimensionsDp(
        options: Bundle?,
        isLandscape: Boolean
    ): Pair<Int, Int> {
        if (options == null) return 0 to 0

        var sizesW = 0
        var sizesH = 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val sizes = try {
                options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            } catch (_: Exception) {
                null
            }
            val validSizes = sizes?.filter { it.width > 40f && it.height > 40f }.orEmpty()
            if (validSizes.isNotEmpty()) {
                val chosen = if (isLandscape) {
                    validSizes.maxByOrNull { it.width }
                } else {
                    val minW = validSizes.minOf { it.width }
                    val portraitCandidates = validSizes.filter { it.width <= minW * 1.18f }
                    portraitCandidates.maxByOrNull { it.height } ?: validSizes.maxByOrNull { it.height }
                }
                if (chosen != null) {
                    sizesW = chosen.width.toInt()
                    sizesH = chosen.height.toInt()
                }
            }
        }

        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)

        val boundsW = if (isLandscape) {
            if (maxW > 40) maxW else minW
        } else {
            if (minW > 40) minW else maxW
        }
        val boundsH = if (isLandscape) {
            if (minH > 40) minH else maxH
        } else {
            maxOf(minH, maxH)
        }

        val resolvedW = when {
            sizesW > 40 && boundsW > 40 -> maxOf(sizesW, boundsW)
            sizesW > 40 -> sizesW
            else -> boundsW
        }

        // Combine OPTION_APPWIDGET_SIZES height with MIN_HEIGHT/MAX_HEIGHT:
        // Some OEM launchers keep OPTION_APPWIDGET_SIZES at the initial 4x2 height (~130-155dp)
        // while updating MAX_HEIGHT/MIN_HEIGHT on resize (or vice versa).
        val resolvedH = when {
            sizesH > 40 && boundsH > 40 -> {
                val sizesIsDefault2Row = sizesH in 125..158
                val boundsIsDefault2Row = boundsH in 125..158
                when {
                    sizesIsDefault2Row && !boundsIsDefault2Row -> boundsH
                    boundsIsDefault2Row && !sizesIsDefault2Row -> sizesH
                    sizesH < 120 || boundsH < 120 -> minOf(sizesH, boundsH)
                    else -> maxOf(sizesH, boundsH)
                }
            }
            sizesH > 40 -> sizesH
            else -> boundsH
        }

        return resolvedW to resolvedH
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
            val isLandscape = context.resources?.configuration?.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE

            val liveOptions = try { appWidgetManager.getAppWidgetOptions(widgetId) } catch (_: Exception) { null }
            val cachedOptions = cachedWidgetOptions[widgetId]
            val cachedAt = cachedWidgetOptionsTimestamp[widgetId] ?: 0L
            val isRecentCache = (System.currentTimeMillis() - cachedAt) in 0L..5000L

            val (liveW, liveH) = extractOptionsDimensionsDp(liveOptions, isLandscape)
            val (cachedW, cachedH) = extractOptionsDimensionsDp(cachedOptions, isLandscape)

            val (resolvedWidthDp, resolvedHeightDp) = if (isRecentCache && cachedW > 40 && cachedH > 40) {
                cachedW to cachedH
            } else if (liveW > 40 && liveH > 40) {
                if (liveOptions != null) {
                    cachedWidgetOptions[widgetId] = Bundle(liveOptions)
                }
                val bestH = if (cachedH > 40 && liveH in 125..158 && cachedH !in 125..158) cachedH else liveH
                val bestW = if (cachedW > 40 && liveW <= 40) cachedW else liveW
                bestW to bestH
            } else {
                cachedW to cachedH
            }

            val wDp = (if (resolvedWidthDp > 40) resolvedWidthDp else defaultWidthDp).coerceIn(110, maxWidthDp)
            val hDp = (if (resolvedHeightDp > 40) resolvedHeightDp else defaultHeightDp).coerceIn(75, maxHeightDp)
            Pair((wDp * density).toInt().coerceAtLeast(100), (hDp * density).toInt().coerceAtLeast(75))
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

    private fun ensureLyricsLoadedForSong(context: Context, song: com.example.data.model.Song?) {
        val songId = song?.id ?: -1L
        if (song == null || songId <= 0L) {
            cachedLyricsSongId = -1L
            cachedSyncedLines = emptyList()
            cachedPlainLines = emptyList()
            cachedLyricsOffsetMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        val hasCached = cachedLyricsSongId == songId && (cachedSyncedLines.isNotEmpty() || cachedPlainLines.isNotEmpty())
        if (hasCached && now - lastLyricsDbCheckMs < 4000L) {
            cachedLyricsOffsetMs = UserPreferencesRepository.getInstance(context.applicationContext).getLyricsSyncOffsetMs(songId)
            return
        }
        if (cachedLyricsSongId == songId && now - lastLyricsDbCheckMs < 2500L) {
            return
        }
        lastLyricsDbCheckMs = now
        try {
            val appContext = context.applicationContext
            val prefs = UserPreferencesRepository.getInstance(appContext)
            cachedLyricsOffsetMs = prefs.getLyricsSyncOffsetMs(songId)

            if (pendingLyricsDbLoadSongIds.add(songId)) {
                val startGen = lyricsCacheGeneration
                widgetScope.launch(Dispatchers.IO) {
                    try {
                        val entity = AppDatabase.getInstance(appContext).lyricsDao().getLyricsForSong(songId)
                        if (lyricsCacheGeneration != startGen) return@launch
                        if (entity != null) {
                            val domainLyrics = com.example.data.model.Lyrics(
                                songId = entity.songId,
                                plainLyrics = entity.plainLyrics,
                                syncedLyricsJson = entity.syncedLyricsJson,
                                isCached = entity.isCached,
                                source = entity.source
                            )
                            val synced = domainLyrics.parseSyncedLines()
                            val plain = if (synced.isEmpty() && !entity.plainLyrics.isNullOrBlank()) {
                                entity.plainLyrics
                                    .lines()
                                    .map { it.replace(Regex("""\[\d{2}:\d{2}(?:\.\d{1,3})?\]"""), "").trim() }
                                    .filter { it.isNotEmpty() }
                            } else {
                                emptyList()
                            }
                            val didChange = cachedLyricsSongId != songId ||
                                cachedSyncedLines != synced ||
                                cachedPlainLines != plain
                            cachedSyncedLines = synced
                            cachedPlainLines = plain
                            if (synced.isEmpty() && plain.isEmpty()) {
                                lyricsFetchCompletedNoLyricsSongIds.add(songId)
                            } else {
                                lyricsFetchCompletedNoLyricsSongIds.remove(songId)
                            }
                            cachedLyricsSongId = songId
                            if (didChange) {
                                lastLyricsLineSignature = ""
                                val liveState = getAuthoritativeState(appContext, PlaybackManager.activeInstance?.playbackState?.value)
                                updateMutex.withLock {
                                    performFullWidgetUpdateInternal(appContext, liveState)
                                }
                            }
                        } else {
                            if (cachedLyricsSongId != songId) {
                                cachedSyncedLines = emptyList()
                                cachedPlainLines = emptyList()
                            }
                            if (attemptedLyricsFetchSongIds.add(songId)) {
                                val db = AppDatabase.getInstance(appContext)
                                val fetcher = com.example.service.LyricsFetcher(db.lyricsDao(), appContext)
                                val fetched = fetcher.getOrFetchLyrics(song)
                                if (lyricsCacheGeneration != startGen) return@launch
                                val hasAnyLyrics = !fetched.syncedLyricsJson.isNullOrBlank() || !fetched.plainLyrics.isNullOrBlank()
                                if (!hasAnyLyrics) {
                                    lyricsFetchCompletedNoLyricsSongIds.add(songId)
                                }
                                cachedLyricsSongId = -1L
                                lastLyricsDbCheckMs = 0L
                                val liveState = getAuthoritativeState(appContext, PlaybackManager.activeInstance?.playbackState?.value)
                                updateMutex.withLock {
                                    performFullWidgetUpdateInternal(appContext, liveState)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        if (lyricsCacheGeneration == startGen) {
                            lyricsFetchCompletedNoLyricsSongIds.add(songId)
                        }
                    } finally {
                        pendingLyricsDbLoadSongIds.remove(songId)
                    }
                }
            }
        } catch (_: Exception) {
            cachedSyncedLines = emptyList()
            cachedPlainLines = emptyList()
        }
    }

    private fun resolveLyricsWindow(
        context: Context,
        song: com.example.data.model.Song?,
        posMs: Long,
        durationMs: Long
    ): LyricsStageWindow {
        if (song == null) {
            return LyricsStageWindow(
                prevLines = emptyList(),
                currLine = "♪ Play a song to view live synced lyrics",
                nextLines = listOf("Tap to open OffGrid Music"),
                signature = "none",
                songPrefix = "",
                mode = LyricsDisplayMode.NONE,
                shouldAnimateTransition = false
            )
        }
        ensureLyricsLoadedForSong(context, song)
        val synced = cachedSyncedLines
        if (synced.isNotEmpty()) {
            val adjustedPos = (posMs + cachedLyricsOffsetMs).coerceAtLeast(0L)
            val activeIdx = synced.indexOfLast { it.first <= adjustedPos }.coerceAtLeast(0)
            val prevList = (1..MAX_SURROUNDING_LYRIC_LINES).mapNotNull { offset ->
                val idx = activeIdx - offset
                if (idx >= 0) synced[idx].second.ifBlank { "♪" } else null
            }
            val curr = synced[activeIdx].second.ifBlank { "♪" }
            val nextList = (1..MAX_SURROUNDING_LYRIC_LINES).mapNotNull { offset ->
                val idx = activeIdx + offset
                if (idx < synced.size) synced[idx].second.ifBlank { "♪" } else null
            }
            val prefix = "sync_${song.id}_"
            return LyricsStageWindow(
                prevLines = prevList,
                currLine = curr,
                nextLines = nextList,
                signature = "${prefix}$activeIdx",
                songPrefix = prefix,
                mode = LyricsDisplayMode.SYNCED,
                shouldAnimateTransition = true
            )
        }
        val plain = cachedPlainLines
        if (plain.isNotEmpty()) {
            val prefix = "plain_${song.id}_"
            val nonBlank = plain.filter { it.isNotBlank() }
            val currLine = nonBlank.getOrNull(0) ?: "Plain lyrics available"
            val nextList = (1..MAX_SURROUNDING_LYRIC_LINES).mapNotNull { offset ->
                nonBlank.getOrNull(offset)
            }.ifEmpty {
                listOf("Unsynced plain text • Tap to view in Now Playing")
            }
            return LyricsStageWindow(
                prevLines = listOf("♪ PLAIN LYRICS • STATIC"),
                currLine = currLine,
                nextLines = nextList,
                signature = "${prefix}plain_static_${plain.hashCode()}",
                songPrefix = prefix,
                mode = LyricsDisplayMode.PLAIN,
                shouldAnimateTransition = false
            )
        }
        val isChecking = song.id in attemptedLyricsFetchSongIds && song.id !in lyricsFetchCompletedNoLyricsSongIds
        return if (isChecking) {
            LyricsStageWindow(
                prevLines = listOf("♪ • •"),
                currLine = "Loading lyrics for ${song.displayTitle}…",
                nextLines = listOf(song.displayArtist),
                signature = "loading_${song.id}",
                songPrefix = "loading_${song.id}_",
                mode = LyricsDisplayMode.LOADING,
                shouldAnimateTransition = false
            )
        } else {
            LyricsStageWindow(
                prevLines = listOf("♪ • •"),
                currLine = "No lyrics available for this song",
                nextLines = listOf("Tap to search or add lyrics in Now Playing"),
                signature = "missing_${song.id}",
                songPrefix = "missing_${song.id}_",
                mode = LyricsDisplayMode.MISSING,
                shouldAnimateTransition = false
            )
        }
    }

    private val PREV_VIEW_IDS_PAGE_0 = intArrayOf(
        R.id.widget_lyrics_prev_0,
        R.id.widget_lyrics_prev2_0,
        R.id.widget_lyrics_prev3_0,
        R.id.widget_lyrics_prev4_0,
        R.id.widget_lyrics_prev5_0,
        R.id.widget_lyrics_prev6_0
    )

    private val NEXT_VIEW_IDS_PAGE_0 = intArrayOf(
        R.id.widget_lyrics_next_0,
        R.id.widget_lyrics_next2_0,
        R.id.widget_lyrics_next3_0,
        R.id.widget_lyrics_next4_0,
        R.id.widget_lyrics_next5_0,
        R.id.widget_lyrics_next6_0
    )

    private val PREV_VIEW_IDS_PAGE_1 = intArrayOf(
        R.id.widget_lyrics_prev_1,
        R.id.widget_lyrics_prev2_1,
        R.id.widget_lyrics_prev3_1,
        R.id.widget_lyrics_prev4_1,
        R.id.widget_lyrics_prev5_1,
        R.id.widget_lyrics_prev6_1
    )

    private val NEXT_VIEW_IDS_PAGE_1 = intArrayOf(
        R.id.widget_lyrics_next_1,
        R.id.widget_lyrics_next2_1,
        R.id.widget_lyrics_next3_1,
        R.id.widget_lyrics_next4_1,
        R.id.widget_lyrics_next5_1,
        R.id.widget_lyrics_next6_1
    )

    private fun bindLyricsFlipperPage(
        views: RemoteViews,
        pageIndex: Int,
        window: LyricsStageWindow,
        spec: AdaptiveLyricsLayoutSpec,
        currColor: Int,
        nearColor: Int,
        midColor: Int,
        farColor: Int
    ) {
        val prevIds = if (pageIndex == 0) PREV_VIEW_IDS_PAGE_0 else PREV_VIEW_IDS_PAGE_1
        val nextIds = if (pageIndex == 0) NEXT_VIEW_IDS_PAGE_0 else NEXT_VIEW_IDS_PAGE_1
        val currId = if (pageIndex == 0) R.id.widget_lyrics_curr_0 else R.id.widget_lyrics_curr_1

        val reserveSymmetricSlots = spec.keepSymmetricCenter && window.mode == LyricsDisplayMode.SYNCED

        // Bind previous lines (index 0 = nearest prev1, index 5 = farthest prev6)
        for (i in 0 until MAX_SURROUNDING_LYRIC_LINES) {
            val viewId = prevIds[i]
            val lineText = window.prevLines.getOrNull(i).orEmpty()
            val shouldShow = i < spec.prevCount && lineText.isNotBlank()
            val reserveEmptySlot = !shouldShow && reserveSymmetricSlots && i < 2
            val sizeSp = when (i) {
                0 -> spec.nearTextSizeSp
                1, 2 -> spec.midTextSizeSp
                else -> spec.farTextSizeSp
            }
            if (shouldShow) {
                val color = when (i) {
                    0 -> nearColor
                    1, 2 -> midColor
                    else -> farColor
                }
                views.setTextViewText(viewId, lineText)
                views.setViewVisibility(viewId, View.VISIBLE)
                views.setTextColor(viewId, color)
                views.setTextViewTextSize(viewId, android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
                views.setViewPadding(viewId, 0, spec.linePadVerticalPx, 0, spec.linePadVerticalPx)
            } else if (reserveEmptySlot) {
                views.setTextViewText(viewId, " ")
                views.setViewVisibility(viewId, View.INVISIBLE)
                views.setTextViewTextSize(viewId, android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
                views.setViewPadding(viewId, 0, spec.linePadVerticalPx, 0, spec.linePadVerticalPx)
            } else {
                views.setTextViewText(viewId, "")
                views.setViewVisibility(viewId, View.GONE)
            }
        }

        // Bind active current line (always visible and prominent)
        views.setTextViewText(currId, window.currLine)
        views.setViewVisibility(currId, View.VISIBLE)
        views.setTextColor(currId, currColor)
        views.setTextViewTextSize(currId, android.util.TypedValue.COMPLEX_UNIT_SP, spec.currTextSizeSp)
        views.setViewPadding(currId, 0, spec.currPadVerticalPx, 0, spec.currPadVerticalPx)

        // Bind upcoming lines (index 0 = nearest next1, index 5 = farthest next6)
        for (i in 0 until MAX_SURROUNDING_LYRIC_LINES) {
            val viewId = nextIds[i]
            val lineText = window.nextLines.getOrNull(i).orEmpty()
            val shouldShow = i < spec.nextCount && lineText.isNotBlank()
            val reserveEmptySlot = !shouldShow && reserveSymmetricSlots && i < 2
            val sizeSp = when (i) {
                0 -> spec.nearTextSizeSp
                1, 2 -> spec.midTextSizeSp
                else -> spec.farTextSizeSp
            }
            if (shouldShow) {
                val color = when (i) {
                    0 -> nearColor
                    1, 2 -> midColor
                    else -> farColor
                }
                views.setTextViewText(viewId, lineText)
                views.setViewVisibility(viewId, View.VISIBLE)
                views.setTextColor(viewId, color)
                views.setTextViewTextSize(viewId, android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
                views.setViewPadding(viewId, 0, spec.linePadVerticalPx, 0, spec.linePadVerticalPx)
            } else if (reserveEmptySlot) {
                views.setTextViewText(viewId, " ")
                views.setViewVisibility(viewId, View.INVISIBLE)
                views.setTextViewTextSize(viewId, android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
                views.setViewPadding(viewId, 0, spec.linePadVerticalPx, 0, spec.linePadVerticalPx)
            } else {
                views.setTextViewText(viewId, "")
                views.setViewVisibility(viewId, View.GONE)
            }
        }
    }

    private fun updateLyricsWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        state: PlaybackState,
        sourceBitmap: android.graphics.Bitmap?,
        themeColors: WidgetBitmapRenderer.WidgetThemeColors,
        isFavorite: Boolean
    ) {
        val song = state.currentSong
        val durationMs = if (state.durationMs > 0L) state.durationMs else (song?.durationMs ?: 0L)
        val posMs = state.positionMs.coerceAtLeast(0L)
        val progressRatio = if (durationMs > 0L) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

        val window = resolveLyricsWindow(context, song, posMs, durationMs)
        lastLyricsLineSignature = window.signature

        val title = song?.displayTitle ?: "OffGrid Music"
        val subtitle = when {
            song == null -> "Live Synced Lyrics"
            window.mode == LyricsDisplayMode.PLAIN -> "${song.displayArtist} • Plain Lyrics"
            window.mode == LyricsDisplayMode.MISSING -> "${song.displayArtist} • No Lyrics"
            else -> song.displayArtist
        }

        val isLight = cachedIsLightTheme
        // Black/Dark vs Light theme colors as per user set theme
        val cardBgColor = if (isLight) {
            android.graphics.Color.parseColor("#F8FAFC")
        } else {
            android.graphics.Color.parseColor("#08090D")
        }
        val cardBorderColor = if (isLight) {
            android.graphics.Color.parseColor("#CBD5E1")
        } else {
            android.graphics.Color.parseColor("#24FFFFFF")
        }
        val titleColor = if (isLight) {
            android.graphics.Color.parseColor("#0F172A")
        } else {
            android.graphics.Color.WHITE
        }
        val subtitleColor = if (isLight) {
            android.graphics.Color.parseColor("#64748B")
        } else {
            android.graphics.Color.parseColor("#94A3B8")
        }
        val currLyricColor = if (isLight) {
            android.graphics.Color.parseColor("#0F172A")
        } else {
            android.graphics.Color.WHITE
        }
        val nearLyricColor = if (isLight) {
            android.graphics.Color.parseColor("#475569")
        } else {
            android.graphics.Color.parseColor("#94A3B8")
        }
        val midLyricColor = if (isLight) {
            android.graphics.Color.parseColor("#64748B")
        } else {
            android.graphics.Color.parseColor("#6B7487")
        }
        val farLyricColor = if (isLight) {
            android.graphics.Color.parseColor("#94A3B8")
        } else {
            android.graphics.Color.parseColor("#4F5768")
        }

        val density = WidgetBitmapRenderer.getRenderDensity(context)
        val artBmp = WidgetBitmapRenderer.renderRoundedArtwork(
            source = sourceBitmap,
            widthPx = (38 * density).toInt(),
            heightPx = (38 * density).toInt(),
            cornerRadiusDp = 10f,
            context = context
        )
        val favBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = (26 * density).toInt(),
            isFavorite = isFavorite,
            accentColor = themeColors.accentColor
        )
        val prevBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (26 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PREV
        )
        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (30 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying
        )
        val nextBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (26 * density).toInt(),
            buttonColor = themeColors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.NEXT
        )
        val progBmp = WidgetBitmapRenderer.renderStraightProgressBar(
            context = context,
            widthPx = (220 * density).toInt(),
            heightPx = (12 * density).toInt(),
            progressRatio = progressRatio,
            accentColor = themeColors.accentColor
        )

        val openNowPlayingIntent = createOpenNowPlayingIntent(context, 50)
        for (widgetId in appWidgetIds) {
            try {
                val (wPx, hPx) = resolveWidgetDimensionsPx(context, appWidgetManager, widgetId, 320, 145, 600, 650)
                val wDp = (wPx / density).toInt()
                val hDp = (hPx / density).toInt().coerceAtLeast(130)
                val layoutSpec = computeAdaptiveLyricsLayout(
                    widthDp = wDp,
                    heightDp = hDp,
                    availablePrevLines = window.prevLines.size,
                    availableNextLines = window.nextLines.size,
                    density = density
                )
                val bgBmp = WidgetBitmapRenderer.renderCardBackground(
                    context = context,
                    widthPx = wPx,
                    heightPx = hPx,
                    cornerRadiusDp = 22f,
                    bgColor = cardBgColor,
                    borderColor = cardBorderColor
                )

                val views = RemoteViews(context.packageName, R.layout.widget_lyrics)
                views.setImageViewBitmap(R.id.widget_lyrics_bg, bgBmp)
                views.setViewPadding(
                    R.id.widget_lyrics_container,
                    layoutSpec.containerPadPx,
                    layoutSpec.containerPadPx,
                    layoutSpec.containerPadPx,
                    layoutSpec.containerPadPx
                )
                views.setImageViewBitmap(R.id.widget_lyrics_artwork, artBmp)
                views.setTextViewText(R.id.widget_lyrics_title, title)
                views.setTextViewText(R.id.widget_lyrics_subtitle, subtitle)
                views.setTextColor(R.id.widget_lyrics_title, titleColor)
                views.setTextColor(R.id.widget_lyrics_subtitle, subtitleColor)

                views.setImageViewBitmap(R.id.widget_lyrics_btn_favorite, favBmp)
                views.setImageViewBitmap(R.id.widget_lyrics_btn_prev, prevBmp)
                views.setImageViewBitmap(R.id.widget_lyrics_btn_play_pause, playPauseBmp)
                views.setImageViewBitmap(R.id.widget_lyrics_btn_next, nextBmp)

                // Populate BOTH Flipper pages with identical current lines and DO NOT call setDisplayedChild here!
                // Omitting setDisplayedChild in full updateAppWidget guarantees that AppWidgetServiceImpl's
                // stored base RemoteViews does not contain a setDisplayedChild action that would re-trigger
                // ViewFlipper's slide animation on every 1-second partial progress update.
                bindLyricsFlipperPage(
                    views = views,
                    pageIndex = 0,
                    window = window,
                    spec = layoutSpec,
                    currColor = currLyricColor,
                    nearColor = nearLyricColor,
                    midColor = midLyricColor,
                    farColor = farLyricColor
                )
                bindLyricsFlipperPage(
                    views = views,
                    pageIndex = 1,
                    window = window,
                    spec = layoutSpec,
                    currColor = currLyricColor,
                    nearColor = nearLyricColor,
                    midColor = midLyricColor,
                    farColor = farLyricColor
                )

                views.setImageViewBitmap(R.id.widget_lyrics_progress, progBmp)
                views.setTextViewText(R.id.widget_lyrics_time_current, formatTime(posMs))
                views.setTextViewText(R.id.widget_lyrics_time_total, formatTime(durationMs))
                views.setTextColor(R.id.widget_lyrics_time_current, subtitleColor)
                views.setTextColor(R.id.widget_lyrics_time_total, subtitleColor)

                views.setOnClickPendingIntent(R.id.widget_lyrics_root, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_lyrics_header_click, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_lyrics_flipper, openNowPlayingIntent)
                views.setOnClickPendingIntent(R.id.widget_lyrics_btn_favorite, createActionIntent(context, LyricsMusicWidgetProvider::class.java, ACTION_WIDGET_FAVORITE, 500))
                views.setOnClickPendingIntent(R.id.widget_lyrics_btn_prev, createActionIntent(context, LyricsMusicWidgetProvider::class.java, ACTION_WIDGET_PREV, 501))
                views.setOnClickPendingIntent(R.id.widget_lyrics_btn_play_pause, createActionIntent(context, LyricsMusicWidgetProvider::class.java, ACTION_WIDGET_PLAY_PAUSE, 502))
                views.setOnClickPendingIntent(R.id.widget_lyrics_btn_next, createActionIntent(context, LyricsMusicWidgetProvider::class.java, ACTION_WIDGET_NEXT, 503))

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
