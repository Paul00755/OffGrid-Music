package com.example.ui.screens

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.auth.UserAccount
import com.example.data.model.FolderInfo
import com.example.data.model.Lyrics
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.data.sync.SyncStatus
import com.example.scanner.ScanProgress
import com.example.service.SleepTimerState
import com.example.ui.components.NavTab
import com.example.ui.viewmodel.MusicViewModel
import java.util.Calendar

private data class SettingsPreviewBundle(
    val liveSong: Song?,
    val liveIsPlaying: Boolean,
    val liveLyrics: Lyrics?,
    val livePlaylists: List<Playlist>,
    val livePlaylistNames: List<String>,
    val liveRecentSongs: List<Song>,
    val liveHomeStats: StudioHomeLiveStats,
    val miniStyle: String,
    val miniPrev: Boolean,
    val miniNext: Boolean,
    val miniFav: Boolean,
    val miniSwipeSkip: Boolean,
    val miniSwipeUp: Boolean,
    val miniSwipeDownDismiss: Boolean,
    val miniProgress: Boolean,
    val miniCircularArt: Boolean
)

private data class SettingsCacheAndDbBundle(
    val cacheLyrics: Boolean,
    val cacheAlbumArt: Boolean,
    val cacheSongArt: Boolean,
    val lyricsCacheSize: String,
    val albumArtCacheSize: String,
    val songArtCacheSize: String,
    val totalCacheSize: String,
    val dbTier: String,
    val dbStats: com.example.data.local.DatabaseStorageStats?,
    val isOptimizingDb: Boolean
)

private data class SettingsAudioAndPlaybackBundle(
    val gapless: Boolean,
    val normalize: Boolean,
    val autoPlayHeadphone: Boolean,
    val autoPlayStartup: Boolean,
    val pauseOnZeroVol: Boolean,
    val autoResumeVol: Boolean,
    val fadeInOutMs: Int,
    val fadeInMs: Int,
    val fadeOutMs: Int,
    val crossfadeSec: Int,
    val shakeToSkip: Boolean,
    val keepScreenAwake: Boolean,
    val audioBalance: Float,
    val eqPreset: String,
    val customEqBands: List<Float>,
    val customBass: Int,
    val customVirt: Int,
    val reduceVolNotif: Boolean,
    val autoPauseSysAudio: Boolean,
    val sleepTimerMode: String,
    val highRefreshRate: Boolean,
    val alwaysShuffle: Boolean,
    val smartQueueEnabled: Boolean,
    val gesturePlayback: Boolean
)

@Composable
private fun rememberSettingsPreviewBundle(
    viewModel: MusicViewModel,
    userAccount: UserAccount?,
    customAvatarUri: String?
): SettingsPreviewBundle {
    val playbackState by viewModel.playbackState.collectAsState()
    val liveLyrics by viewModel.currentLyrics.collectAsState()
    val allPlaylists by viewModel.allPlaylists.collectAsState()
    val allSongs by viewModel.allSongs.collectAsState()
    val recentSongs by viewModel.recentlyPlayedSongs.collectAsState()
    val miniStyle by viewModel.miniPlayerStyle.collectAsState()
    val miniPrev by viewModel.miniPlayerShowPrevious.collectAsState()
    val miniNext by viewModel.miniPlayerShowNext.collectAsState()
    val miniFav by viewModel.miniPlayerShowFavorite.collectAsState()
    val miniSwipeSkip by viewModel.miniPlayerSwipeToSkip.collectAsState()
    val miniSwipeUp by viewModel.miniPlayerSwipeUpToOpen.collectAsState()
    val miniSwipeDownDismiss by viewModel.miniPlayerSwipeDownToDismiss.collectAsState()
    val miniProgress by viewModel.miniPlayerShowProgress.collectAsState()
    val miniCircularArt by viewModel.miniPlayerCircularArt.collectAsState()

    val timeBounds = remember {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - 24L * 60 * 60 * 1000
        val weekStart = todayStart - 6L * 24 * 60 * 60 * 1000
        val monthStart = todayStart - 29L * 24 * 60 * 60 * 1000
        listOf(todayStart, yesterdayStart, weekStart, monthStart)
    }
    val todayStart = timeBounds[0]
    val yesterdayStart = timeBounds[1]
    val weekStart = timeBounds[2]
    val monthStart = timeBounds[3]

    val todayDur by remember(todayStart) { viewModel.getTotalDurationPlayedBetween(todayStart) }.collectAsState(initial = 0L)
    val todayPlays by remember(todayStart) { viewModel.getTrackCountBetween(todayStart) }.collectAsState(initial = 0)
    val todayUnique by remember(todayStart) { viewModel.getUniqueTrackCountBetween(todayStart) }.collectAsState(initial = 0)

    val yesterdayDur by remember(yesterdayStart, todayStart) { viewModel.getTotalDurationPlayedBetween(yesterdayStart, todayStart) }.collectAsState(initial = 0L)
    val yesterdayPlays by remember(yesterdayStart, todayStart) { viewModel.getTrackCountBetween(yesterdayStart, todayStart) }.collectAsState(initial = 0)
    val yesterdayUnique by remember(yesterdayStart, todayStart) { viewModel.getUniqueTrackCountBetween(yesterdayStart, todayStart) }.collectAsState(initial = 0)

    val weekDur by remember(weekStart) { viewModel.getTotalDurationPlayedBetween(weekStart) }.collectAsState(initial = 0L)
    val weekPlays by remember(weekStart) { viewModel.getTrackCountBetween(weekStart) }.collectAsState(initial = 0)
    val weekUnique by remember(weekStart) { viewModel.getUniqueTrackCountBetween(weekStart) }.collectAsState(initial = 0)

    val monthDur by remember(monthStart) { viewModel.getTotalDurationPlayedBetween(monthStart) }.collectAsState(initial = 0L)
    val monthPlays by remember(monthStart) { viewModel.getTrackCountBetween(monthStart) }.collectAsState(initial = 0)

    val allTimeDur by remember { viewModel.getTotalDurationPlayedBetween(0L) }.collectAsState(initial = 0L)
    val allTimePlays by remember { viewModel.getTrackCountBetween(0L) }.collectAsState(initial = 0)

    val playlistNames = remember(allPlaylists) { allPlaylists.map { it.name } }
    val recentTop = remember(recentSongs) { recentSongs.take(4) }
    val effectiveAvatar = customAvatarUri?.takeIf { it.isNotBlank() } ?: userAccount?.photoUrl

    val homeStats = StudioHomeLiveStats(
        todayDurationMs = todayDur,
        todayPlays = todayPlays,
        todayUniqueTracks = todayUnique,
        yesterdayDurationMs = yesterdayDur,
        yesterdayPlays = yesterdayPlays,
        yesterdayUniqueTracks = yesterdayUnique,
        weekDurationMs = weekDur,
        weekPlays = weekPlays,
        weekUniqueTracks = weekUnique,
        monthDurationMs = monthDur,
        monthPlays = monthPlays,
        allTimeDurationMs = allTimeDur,
        allTimePlays = allTimePlays,
        totalCatalogSongs = allSongs.size,
        userEmail = userAccount?.email,
        userAvatarUri = effectiveAvatar,
        playbackPositionMs = playbackState.positionMs
    )

    return SettingsPreviewBundle(
        liveSong = playbackState.currentSong,
        liveIsPlaying = playbackState.isPlaying,
        liveLyrics = liveLyrics,
        livePlaylists = allPlaylists,
        livePlaylistNames = playlistNames,
        liveRecentSongs = recentTop,
        liveHomeStats = homeStats,
        miniStyle = miniStyle,
        miniPrev = miniPrev,
        miniNext = miniNext,
        miniFav = miniFav,
        miniSwipeSkip = miniSwipeSkip,
        miniSwipeUp = miniSwipeUp,
        miniSwipeDownDismiss = miniSwipeDownDismiss,
        miniProgress = miniProgress,
        miniCircularArt = miniCircularArt
    )
}

@Composable
private fun rememberSettingsCacheAndDbBundle(viewModel: MusicViewModel): SettingsCacheAndDbBundle {
    return SettingsCacheAndDbBundle(
        cacheLyrics = viewModel.cacheLyricsEnabled.collectAsState().value,
        cacheAlbumArt = viewModel.cacheAlbumArtEnabled.collectAsState().value,
        cacheSongArt = viewModel.cacheSongArtWorkEnabled.collectAsState().value,
        lyricsCacheSize = viewModel.lyricsCacheSize.collectAsState().value,
        albumArtCacheSize = viewModel.albumArtCacheSize.collectAsState().value,
        songArtCacheSize = viewModel.songArtCacheSize.collectAsState().value,
        totalCacheSize = viewModel.totalCacheSize.collectAsState().value,
        dbTier = viewModel.databaseStorageTier.collectAsState().value,
        dbStats = viewModel.databaseStorageStats.collectAsState().value,
        isOptimizingDb = viewModel.isOptimizingDatabase.collectAsState().value
    )
}

@Composable
private fun rememberSettingsAudioAndPlaybackBundle(viewModel: MusicViewModel): SettingsAudioAndPlaybackBundle {
    return SettingsAudioAndPlaybackBundle(
        gapless = viewModel.gaplessPlayback.collectAsState().value,
        normalize = viewModel.normalizeAudio.collectAsState().value,
        autoPlayHeadphone = viewModel.autoPlayOnHeadphoneConnection.collectAsState().value,
        autoPlayStartup = viewModel.autoPlayOnAppStartup.collectAsState().value,
        pauseOnZeroVol = viewModel.pauseOnZeroVolume.collectAsState().value,
        autoResumeVol = viewModel.autoResumeOnVolumeIncrease.collectAsState().value,
        fadeInOutMs = viewModel.fadeInOutMs.collectAsState().value,
        fadeInMs = viewModel.fadeInMs.collectAsState().value,
        fadeOutMs = viewModel.fadeOutMs.collectAsState().value,
        crossfadeSec = viewModel.crossfadeDurationSec.collectAsState().value,
        shakeToSkip = viewModel.shakeToSkip.collectAsState().value,
        keepScreenAwake = viewModel.keepScreenAwake.collectAsState().value,
        audioBalance = viewModel.audioBalance.collectAsState().value,
        eqPreset = viewModel.equalizerPreset.collectAsState().value,
        customEqBands = viewModel.customEqBands.collectAsState().value,
        customBass = viewModel.customBassBoost.collectAsState().value,
        customVirt = viewModel.customVirtualizer.collectAsState().value,
        reduceVolNotif = viewModel.reduceVolumeOnNotification.collectAsState().value,
        autoPauseSysAudio = viewModel.autoPauseSystemAudio.collectAsState().value,
        sleepTimerMode = viewModel.sleepTimerMode.collectAsState().value,
        highRefreshRate = viewModel.highRefreshRate.collectAsState().value,
        alwaysShuffle = viewModel.alwaysShuffleMode.collectAsState().value,
        smartQueueEnabled = viewModel.smartQueueEnabled.collectAsState().value,
        gesturePlayback = viewModel.gesturePlaybackEnabled.collectAsState().value
    )
}

@Composable
private fun SettingsHardwareSideEffects(
    viewModel: MusicViewModel,
    keepScreenAwake: Boolean,
    shakeToSkip: Boolean
) {
    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(Unit) {
        viewModel.refreshCacheSizes()
        viewModel.refreshDatabaseStats()
    }

    DisposableEffect(keepScreenAwake) {
        if (keepScreenAwake) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(shakeToSkip) {
        if (!shakeToSkip) return@DisposableEffect onDispose {}
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var lastShakeTime = 0L

        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                if (event == null) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val gForce = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat() / android.hardware.SensorManager.GRAVITY_EARTH
                if (gForce > 2.4f) {
                    val now = System.currentTimeMillis()
                    if (now - lastShakeTime > 1200L) {
                        lastShakeTime = now
                        viewModel.playbackManager.skipToNext()
                    }
                }
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }

        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(listener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }
}

@Composable
fun SettingsTabRoute(
    viewModel: MusicViewModel,
    allFolders: List<FolderInfo>,
    sleepTimerState: SleepTimerState,
    scanProgress: ScanProgress,
    userAccount: UserAccount?,
    effectiveDisplayName: String,
    customAvatarUri: String?,
    syncStatus: SyncStatus,
    uiTheme: String,
    homeScreenLayout: String,
    nowPlayingLayout: String,
    playlistsLayout: String,
    lyricsLayout: String,
    isDynamicArtworkTheme: Boolean,
    customAccentColor: Int,
    accentColorPreset: String,
    recentSearches: List<String>,
    homeSections: List<String>
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val preview = rememberSettingsPreviewBundle(viewModel, userAccount, customAvatarUri)
    val cacheDb = rememberSettingsCacheAndDbBundle(viewModel)
    val audio = rememberSettingsAudioAndPlaybackBundle(viewModel)

    SettingsHardwareSideEffects(
        viewModel = viewModel,
        keepScreenAwake = audio.keepScreenAwake,
        shakeToSkip = audio.shakeToSkip
    )

    val waveformStyle by viewModel.waveformStyle.collectAsState()
    val isWaveformUserOverridden by viewModel.isWaveformUserOverridden.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val displayScale by viewModel.displayScale.collectAsState()
    val showNowPlayingVolumeBar by viewModel.showNowPlayingVolumeBar.collectAsState()
    val showAdvancedSongInfo by viewModel.showAdvancedSongInfo.collectAsState()
    val showNowPlayingSeekButtons by viewModel.showNowPlayingSeekButtons.collectAsState()
    val nowPlayingBackwardSeconds by viewModel.nowPlayingBackwardSeconds.collectAsState()
    val nowPlayingForwardSeconds by viewModel.nowPlayingForwardSeconds.collectAsState()
    val useDarkColors by viewModel.useDarkColors.collectAsState()
    val isGradientEffect by viewModel.isGradientEffect.collectAsState()
    val isGlassEffect by viewModel.isGlassEffect.collectAsState()
    val isGradientGlassEffect by viewModel.isGradientGlassEffect.collectAsState()
    val fetchOnlineArtworkHighRes by viewModel.fetchOnlineArtworkHighRes.collectAsState()
    val lyricsAutoScroll by viewModel.lyricsAutoScroll.collectAsState()

    SettingsScreen(
        folders = allFolders,
        sleepTimerState = sleepTimerState,
        waveformStyle = waveformStyle,
        isWaveformUserOverridden = isWaveformUserOverridden,
        onSetWaveformStyle = viewModel::setWaveformStyle,
        themeMode = themeMode,
        onSetThemeMode = viewModel::setThemeMode,
        uiTheme = uiTheme,
        onSetUiTheme = viewModel::setUiTheme,
        displayScale = displayScale,
        onSetDisplayScale = viewModel::setDisplayScale,
        homeScreenLayout = homeScreenLayout,
        onSetHomeScreenLayout = viewModel::setHomeScreenLayout,
        nowPlayingLayout = nowPlayingLayout,
        onSetNowPlayingLayout = viewModel::setNowPlayingLayout,
        playlistsLayout = playlistsLayout,
        onSetPlaylistsLayout = viewModel::setPlaylistsLayout,
        lyricsLayout = lyricsLayout,
        onSetLyricsLayout = viewModel::setLyricsLayout,
        showNowPlayingVolumeBar = showNowPlayingVolumeBar,
        onSetShowNowPlayingVolumeBar = viewModel::setShowNowPlayingVolumeBar,
        showAdvancedSongInfo = showAdvancedSongInfo,
        onSetShowAdvancedSongInfo = viewModel::setShowAdvancedSongInfo,
        showNowPlayingSeekButtons = showNowPlayingSeekButtons,
        onSetShowNowPlayingSeekButtons = viewModel::setShowNowPlayingSeekButtons,
        nowPlayingBackwardSeconds = nowPlayingBackwardSeconds,
        onSetNowPlayingBackwardSeconds = viewModel::setNowPlayingBackwardSeconds,
        nowPlayingForwardSeconds = nowPlayingForwardSeconds,
        onSetNowPlayingForwardSeconds = viewModel::setNowPlayingForwardSeconds,
        isDynamicArtworkTheme = isDynamicArtworkTheme,
        onSetDynamicArtworkTheme = viewModel::setDynamicArtworkTheme,
        useDarkColors = useDarkColors,
        onSetUseDarkColors = viewModel::setUseDarkColors,
        isGradientEffect = isGradientEffect,
        onSetGradientEffect = viewModel::setGradientEffect,
        isGlassEffect = isGlassEffect,
        onSetGlassEffect = viewModel::setGlassEffect,
        isGradientGlassEffect = isGradientGlassEffect,
        onSetGradientGlassEffect = viewModel::setGradientGlassEffect,
        fetchOnlineArtworkHighRes = fetchOnlineArtworkHighRes,
        onSetFetchOnlineArtworkHighRes = viewModel::setFetchOnlineArtworkHighRes,
        cacheLyricsEnabled = cacheDb.cacheLyrics,
        onSetCacheLyricsEnabled = viewModel::setCacheLyricsEnabled,
        onClearLyricsCache = viewModel::clearLyricsCache,
        cacheAlbumArtEnabled = cacheDb.cacheAlbumArt,
        onSetCacheAlbumArtEnabled = viewModel::setCacheAlbumArtEnabled,
        onClearAlbumArtCache = viewModel::clearAlbumArtCache,
        cacheSongArtWorkEnabled = cacheDb.cacheSongArt,
        onSetCacheSongArtWorkEnabled = viewModel::setCacheSongArtWorkEnabled,
        onClearSongArtCache = viewModel::clearSongArtCache,
        lyricsCacheSize = cacheDb.lyricsCacheSize,
        albumArtCacheSize = cacheDb.albumArtCacheSize,
        songArtCacheSize = cacheDb.songArtCacheSize,
        totalCacheSize = cacheDb.totalCacheSize,
        onRefreshCacheSizes = viewModel::refreshCacheSizes,
        onClearAllCaches = viewModel::clearAllCaches,
        databaseStorageTier = cacheDb.dbTier,
        onSetDatabaseStorageTier = viewModel::setDatabaseStorageTier,
        databaseStorageStats = cacheDb.dbStats,
        isOptimizingDatabase = cacheDb.isOptimizingDb,
        onOptimizeDatabase = viewModel::optimizeDatabase,
        onRefreshDatabaseStats = viewModel::refreshDatabaseStats,
        customEqBands = audio.customEqBands,
        onSetCustomEqBands = viewModel::setCustomEqBands,
        customBassBoost = audio.customBass,
        onSetCustomBassBoost = viewModel::setCustomBassBoost,
        customVirtualizer = audio.customVirt,
        onSetCustomVirtualizer = viewModel::setCustomVirtualizer,
        customAccentColor = customAccentColor,
        onSetCustomAccentColor = viewModel::setCustomAccentColor,
        accentColorPreset = accentColorPreset,
        onSetAccentColorPreset = viewModel::setAccentColorPreset,
        miniPlayerStyle = preview.miniStyle,
        onSetMiniPlayerStyle = viewModel::setMiniPlayerStyle,
        miniPlayerShowPrevious = preview.miniPrev,
        onSetMiniPlayerShowPrevious = viewModel::setMiniPlayerShowPrevious,
        miniPlayerShowNext = preview.miniNext,
        onSetMiniPlayerShowNext = viewModel::setMiniPlayerShowNext,
        miniPlayerShowFavorite = preview.miniFav,
        onSetMiniPlayerShowFavorite = viewModel::setMiniPlayerShowFavorite,
        miniPlayerSwipeToSkip = preview.miniSwipeSkip,
        onSetMiniPlayerSwipeToSkip = viewModel::setMiniPlayerSwipeToSkip,
        miniPlayerSwipeUpToOpen = preview.miniSwipeUp,
        onSetMiniPlayerSwipeUpToOpen = viewModel::setMiniPlayerSwipeUpToOpen,
        miniPlayerSwipeDownToDismiss = preview.miniSwipeDownDismiss,
        onSetMiniPlayerSwipeDownToDismiss = viewModel::setMiniPlayerSwipeDownToDismiss,
        miniPlayerShowProgress = preview.miniProgress,
        onSetMiniPlayerShowProgress = viewModel::setMiniPlayerShowProgress,
        miniPlayerCircularArt = preview.miniCircularArt,
        onSetMiniPlayerCircularArt = viewModel::setMiniPlayerCircularArt,
        livePreviewSong = preview.liveSong,
        livePreviewIsPlaying = preview.liveIsPlaying,
        livePreviewLyrics = preview.liveLyrics,
        livePreviewPlaylists = preview.livePlaylists,
        livePreviewPlaylistNames = preview.livePlaylistNames,
        livePreviewRecentSongs = preview.liveRecentSongs,
        livePreviewHomeStats = preview.liveHomeStats,
        gaplessPlayback = audio.gapless,
        onSetGaplessPlayback = viewModel::setGaplessPlayback,
        normalizeAudio = audio.normalize,
        onSetNormalizeAudio = viewModel::setNormalizeAudio,
        autoPlayOnHeadphoneConnection = audio.autoPlayHeadphone,
        onSetAutoPlayOnHeadphoneConnection = viewModel::setAutoPlayOnHeadphoneConnection,
        autoPlayOnAppStartup = audio.autoPlayStartup,
        onSetAutoPlayOnAppStartup = viewModel::setAutoPlayOnAppStartup,
        isTranscribeTranslateEnabled = viewModel.isTranscribeTranslateEnabled.collectAsState().value,
        onSetTranscribeTranslateEnabled = viewModel::setTranscribeTranslateEnabled,
        audioTranscribeTargetLang = viewModel.audioTranscribeTargetLang.collectAsState().value,
        onSetAudioTranscribeTargetLang = viewModel::setAudioTranscribeTargetLang,
        hasUserApiKey = viewModel.hasUserApiKey.collectAsState().value,
        maskedApiKey = viewModel.maskedApiKey.collectAsState().value,
        isKeyValidating = viewModel.isKeyValidating.collectAsState().value,
        keyValidationErrorMessage = viewModel.keyValidationErrorMessage.collectAsState().value,
        onSaveUserApiKey = viewModel::saveUserApiKey,
        onDeleteUserApiKey = viewModel::deleteUserApiKey,
        onClearKeyValidationError = viewModel::clearKeyValidationError,
        pauseOnZeroVolume = audio.pauseOnZeroVol,
        onSetPauseOnZeroVolume = viewModel::setPauseOnZeroVolume,
        autoResumeOnVolumeIncrease = audio.autoResumeVol,
        onSetAutoResumeOnVolumeIncrease = viewModel::setAutoResumeOnVolumeIncrease,
        fadeInOutMs = audio.fadeInOutMs,
        onSetFadeInOutMs = viewModel::setFadeInOutMs,
        crossfadeDurationSec = audio.crossfadeSec,
        onSetCrossfadeDurationSec = viewModel::setCrossfadeDurationSec,
        ignoreAudioDuration = viewModel.ignoreAudioDuration.collectAsState().value,
        onSetIgnoreAudioDuration = viewModel::setIgnoreAudioDuration,
        maxSongDuration = viewModel.maxSongDuration.collectAsState().value,
        fullDeviceScan = viewModel.fullDeviceScan.collectAsState().value,
        onSetFullDeviceScan = viewModel::setFullDeviceScan,
        storeIndexedSongsLocally = viewModel.storeIndexedSongsLocally.collectAsState().value,
        onSetStoreIndexedSongsLocally = viewModel::setStoreIndexedSongsLocally,
        localSongSyncProgress = viewModel.localSongSyncProgress.collectAsState().value,
        onSetSongDurationRange = viewModel::setSongDurationRange,
        fadeInMs = audio.fadeInMs,
        onSetFadeInMs = viewModel::setFadeInMs,
        fadeOutMs = audio.fadeOutMs,
        onSetFadeOutMs = viewModel::setFadeOutMs,
        shakeToSkip = audio.shakeToSkip,
        onSetShakeToSkip = viewModel::setShakeToSkip,
        keepScreenAwake = audio.keepScreenAwake,
        onSetKeepScreenAwake = viewModel::setKeepScreenAwake,
        audioBalance = audio.audioBalance,
        onSetAudioBalance = viewModel::setAudioBalance,
        equalizerPreset = audio.eqPreset,
        onSetEqualizerPreset = viewModel::setEqualizerPreset,
        lyricsAutoScroll = lyricsAutoScroll,
        onSetLyricsAutoScroll = viewModel::setLyricsAutoScroll,
        reduceVolumeOnNotification = audio.reduceVolNotif,
        onSetReduceVolumeOnNotification = viewModel::setReduceVolumeOnNotification,
        autoPauseSystemAudio = audio.autoPauseSysAudio,
        onSetAutoPauseSystemAudio = viewModel::setAutoPauseSystemAudio,
        onAddFolder = viewModel::addFolder,
        onRemoveFolder = viewModel::removeFolder,
        scrobbleThresholdType = viewModel.scrobbleThresholdType.collectAsState().value,
        onSetScrobbleThresholdType = viewModel::setScrobbleThresholdType,
        scrobbleThresholdValue = viewModel.scrobbleThresholdValue.collectAsState().value,
        onSetScrobbleThresholdValue = viewModel::setScrobbleThresholdValue,
        recentSearches = recentSearches,
        onDeleteRecentSearch = viewModel::deleteRecentSearch,
        onClearRecentSearches = viewModel::clearRecentSearches,
        sleepTimerMode = audio.sleepTimerMode,
        onSetSleepTimerMode = viewModel::setSleepTimerMode,
        onStartSleepTimer = viewModel::startSleepTimerMinutes,
        onStartSongCountTimer = viewModel::startSleepTimerSongs,
        onAddSleepTimerMinutes = viewModel::addSleepTimerMinutes,
        onAddSleepTimerSongs = viewModel::addSleepTimerSongs,
        onSetSleepTimerFinishLastSong = viewModel::setSleepTimerFinishLastSong,
        onCancelSleepTimer = viewModel::cancelSleepTimer,
        onScanLibrary = { viewModel.scanLibrary() },
        onQuickScanAndCleanLibrary = viewModel::quickScanAndCleanLibrary,
        onBackClick = { viewModel.selectedTab.value = NavTab.HOME },
        highRefreshRate = audio.highRefreshRate,
        onSetHighRefreshRate = viewModel::setHighRefreshRate,
        alwaysShuffleMode = audio.alwaysShuffle,
        onSetAlwaysShuffleMode = viewModel::setAlwaysShuffleMode,
        smartQueueEnabled = audio.smartQueueEnabled,
        onSetSmartQueueEnabled = viewModel::setSmartQueueEnabled,
        gesturePlaybackEnabled = audio.gesturePlayback,
        onSetGesturePlaybackEnabled = viewModel::setGesturePlaybackEnabled,
        homeSections = homeSections,
        onSetHomeSections = viewModel::setHomeSections,
        bottomNavOrder = viewModel.bottomNavOrder.collectAsState().value,
        onSetBottomNavOrder = viewModel::setBottomNavOrder,
        onResetBottomNavOrder = viewModel::resetBottomNavOrder,
        autoOpenSearchKeyboard = viewModel.autoOpenSearchKeyboard.collectAsState().value,
        onSetAutoOpenSearchKeyboard = viewModel::setAutoOpenSearchKeyboard,
        customHomeFeedPresets = viewModel.customHomeFeedPresets.collectAsState().value,
        onSaveCustomHomeFeedPreset = viewModel::saveCustomHomeFeedPreset,
        onDeleteCustomHomeFeedPreset = viewModel::deleteCustomHomeFeedPreset,
        addToQueuePosition = viewModel.addToQueuePosition.collectAsState().value,
        onSetAddToQueuePosition = viewModel::setAddToQueuePosition,
        playlistAddSongPosition = viewModel.playlistAddSongPosition.collectAsState().value,
        onSetPlaylistAddSongPosition = viewModel::setPlaylistAddSongPosition,
        scanProgress = scanProgress,
        userAccount = userAccount,
        effectiveDisplayName = effectiveDisplayName,
        customAvatarUri = customAvatarUri,
        syncStatus = syncStatus,
        backupStatus = viewModel.backupStatus.collectAsState().value,
        lastSyncedTimestamp = viewModel.lastSyncedTimestamp.collectAsState().value,
        lastBackupTimestamp = viewModel.lastBackupTimestamp.collectAsState().value,
        automaticBackupEnabled = viewModel.automaticBackupEnabled.collectAsState().value,
        backupFrequency = viewModel.backupFrequency.collectAsState().value,
        onToggleAutomaticBackup = viewModel::setAutomaticBackupEnabled,
        onSelectFrequency = viewModel::setBackupFrequency,
        onTriggerSync = viewModel::triggerCloudSync,
        onTriggerBackup = viewModel::triggerBackupNow,
        onSignOut = viewModel::signOut,
        onOpenAccount = viewModel::openAccountScreen,
        onOpenAuth = viewModel::openAuthScreen,
        onSignInGoogle = { activity?.let { viewModel.signInWithGoogle(it) } ?: viewModel.openAuthScreen() },
        initialTargetSection = viewModel.settingsTargetSection.collectAsState().value,
        onTargetSectionConsumed = { viewModel.settingsTargetSection.value = null },
        updateCheckResult = viewModel.updateCheckResult.collectAsState().value,
        updateNotificationsEnabled = viewModel.updateNotificationsEnabled.collectAsState().value,
        onSetUpdateNotificationsEnabled = viewModel::setUpdateNotificationsEnabled,
        onCheckForUpdates = { viewModel.checkForUpdates(isManual = true) },
        onOpenUpdateDialog = viewModel::reopenOrCheckForUpdates,
        onResetToDefaultSettings = viewModel::resetToDefaultSettings
    )
}
