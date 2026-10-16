package com.example.ui.screens

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.auth.UserAccount
import com.example.data.model.FolderInfo
import com.example.data.sync.SyncStatus
import com.example.scanner.ScanProgress
import com.example.service.SleepTimerState
import com.example.ui.components.NavTab
import com.example.ui.viewmodel.MusicViewModel

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
    isDynamicArtworkTheme: Boolean,
    customAccentColor: Int,
    accentColorPreset: String,
    recentSearches: List<String>,
    homeSections: List<String>
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val reduceVolumeOnNotification by viewModel.reduceVolumeOnNotification.collectAsState()
    val waveformStyle by viewModel.waveformStyle.collectAsState()
    val miniPlayerShowPrevious by viewModel.miniPlayerShowPrevious.collectAsState()
    val miniPlayerShowNext by viewModel.miniPlayerShowNext.collectAsState()
    val miniPlayerShowFavorite by viewModel.miniPlayerShowFavorite.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val gaplessPlayback by viewModel.gaplessPlayback.collectAsState()
    val normalizeAudio by viewModel.normalizeAudio.collectAsState()
    val pauseOnZeroVolume by viewModel.pauseOnZeroVolume.collectAsState()
    val playDuringCamera by viewModel.playDuringCamera.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshCacheSizes()
        viewModel.refreshDatabaseStats()
    }
    val fadeInOutMs by viewModel.fadeInOutMs.collectAsState()
    val crossfadeDurationSec by viewModel.crossfadeDurationSec.collectAsState()
    val shakeToSkip by viewModel.shakeToSkip.collectAsState()
    val keepScreenAwake by viewModel.keepScreenAwake.collectAsState()
    val audioBalance by viewModel.audioBalance.collectAsState()
    val equalizerPreset by viewModel.equalizerPreset.collectAsState()
    val lyricsAutoScroll by viewModel.lyricsAutoScroll.collectAsState()

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

    SettingsScreen(
        folders = allFolders,
        sleepTimerState = sleepTimerState,
        waveformStyle = waveformStyle,
        onSetWaveformStyle = { viewModel.setWaveformStyle(it) },
        themeMode = themeMode,
        onSetThemeMode = { viewModel.setThemeMode(it) },
        uiTheme = uiTheme,
        onSetUiTheme = { viewModel.setUiTheme(it) },
        homeScreenLayout = homeScreenLayout,
        onSetHomeScreenLayout = { viewModel.setHomeScreenLayout(it) },
        nowPlayingLayout = nowPlayingLayout,
        onSetNowPlayingLayout = { viewModel.setNowPlayingLayout(it) },
        playlistsLayout = playlistsLayout,
        onSetPlaylistsLayout = { viewModel.setPlaylistsLayout(it) },
        isDynamicArtworkTheme = isDynamicArtworkTheme,
        onSetDynamicArtworkTheme = { viewModel.setDynamicArtworkTheme(it) },
        fetchOnlineArtworkHighRes = viewModel.fetchOnlineArtworkHighRes.collectAsState().value,
        onSetFetchOnlineArtworkHighRes = { viewModel.setFetchOnlineArtworkHighRes(it) },
        cacheLyricsEnabled = viewModel.cacheLyricsEnabled.collectAsState().value,
        onSetCacheLyricsEnabled = { viewModel.setCacheLyricsEnabled(it) },
        onClearLyricsCache = { viewModel.clearLyricsCache() },
        cacheAlbumArtEnabled = viewModel.cacheAlbumArtEnabled.collectAsState().value,
        onSetCacheAlbumArtEnabled = { viewModel.setCacheAlbumArtEnabled(it) },
        onClearAlbumArtCache = { viewModel.clearAlbumArtCache() },
        cacheSongArtWorkEnabled = viewModel.cacheSongArtWorkEnabled.collectAsState().value,
        onSetCacheSongArtWorkEnabled = { viewModel.setCacheSongArtWorkEnabled(it) },
        onClearSongArtCache = { viewModel.clearSongArtCache() },
        lyricsCacheSize = viewModel.lyricsCacheSize.collectAsState().value,
        albumArtCacheSize = viewModel.albumArtCacheSize.collectAsState().value,
        songArtCacheSize = viewModel.songArtCacheSize.collectAsState().value,
        totalCacheSize = viewModel.totalCacheSize.collectAsState().value,
        onRefreshCacheSizes = { viewModel.refreshCacheSizes() },
        onClearAllCaches = { viewModel.clearAllCaches() },
        databaseStorageTier = viewModel.databaseStorageTier.collectAsState().value,
        onSetDatabaseStorageTier = { viewModel.setDatabaseStorageTier(it) },
        databaseStorageStats = viewModel.databaseStorageStats.collectAsState().value,
        isOptimizingDatabase = viewModel.isOptimizingDatabase.collectAsState().value,
        onOptimizeDatabase = { onComplete -> viewModel.optimizeDatabase(onComplete) },
        onExportDatabaseBackup = { onComplete -> viewModel.exportDatabaseBackup(onComplete) },
        onImportDatabaseBackup = { json, onComplete -> viewModel.importDatabaseBackup(json, onComplete) },
        onRefreshDatabaseStats = { viewModel.refreshDatabaseStats() },
        customEqBands = viewModel.customEqBands.collectAsState().value,
        onSetCustomEqBands = { viewModel.setCustomEqBands(it) },
        customBassBoost = viewModel.customBassBoost.collectAsState().value,
        onSetCustomBassBoost = { viewModel.setCustomBassBoost(it) },
        customVirtualizer = viewModel.customVirtualizer.collectAsState().value,
        onSetCustomVirtualizer = { viewModel.setCustomVirtualizer(it) },
        customAccentColor = customAccentColor,
        onSetCustomAccentColor = { viewModel.setCustomAccentColor(it) },
        accentColorPreset = accentColorPreset,
        onSetAccentColorPreset = { viewModel.setAccentColorPreset(it) },
        miniPlayerStyle = viewModel.miniPlayerStyle.collectAsState().value,
        onSetMiniPlayerStyle = { viewModel.setMiniPlayerStyle(it) },
        miniPlayerShowPrevious = miniPlayerShowPrevious,
        onSetMiniPlayerShowPrevious = { viewModel.setMiniPlayerShowPrevious(it) },
        miniPlayerShowNext = miniPlayerShowNext,
        onSetMiniPlayerShowNext = { viewModel.setMiniPlayerShowNext(it) },
        miniPlayerShowFavorite = miniPlayerShowFavorite,
        onSetMiniPlayerShowFavorite = { viewModel.setMiniPlayerShowFavorite(it) },
        gaplessPlayback = gaplessPlayback,
        onSetGaplessPlayback = { viewModel.setGaplessPlayback(it) },
        normalizeAudio = normalizeAudio,
        onSetNormalizeAudio = { viewModel.setNormalizeAudio(it) },
        autoPlayOnHeadphoneConnection = viewModel.autoPlayOnHeadphoneConnection.collectAsState().value,
        onSetAutoPlayOnHeadphoneConnection = { viewModel.setAutoPlayOnHeadphoneConnection(it) },
        autoPlayOnAppStartup = viewModel.autoPlayOnAppStartup.collectAsState().value,
        onSetAutoPlayOnAppStartup = { viewModel.setAutoPlayOnAppStartup(it) },
        isTranscribeTranslateEnabled = viewModel.isTranscribeTranslateEnabled.collectAsState().value,
        onSetTranscribeTranslateEnabled = { viewModel.setTranscribeTranslateEnabled(it) },
        hasUserApiKey = viewModel.hasUserApiKey.collectAsState().value,
        maskedApiKey = viewModel.maskedApiKey.collectAsState().value,
        isKeyValidating = viewModel.isKeyValidating.collectAsState().value,
        keyValidationErrorMessage = viewModel.keyValidationErrorMessage.collectAsState().value,
        onSaveUserApiKey = { key, cb -> viewModel.saveUserApiKey(key, cb) },
        onDeleteUserApiKey = { viewModel.deleteUserApiKey() },
        onClearKeyValidationError = { viewModel.clearKeyValidationError() },
        pauseOnZeroVolume = pauseOnZeroVolume,
        onSetPauseOnZeroVolume = { viewModel.setPauseOnZeroVolume(it) },
        autoResumeOnVolumeIncrease = viewModel.autoResumeOnVolumeIncrease.collectAsState().value,
        onSetAutoResumeOnVolumeIncrease = { viewModel.setAutoResumeOnVolumeIncrease(it) },
        fadeInOutMs = fadeInOutMs,
        onSetFadeInOutMs = { viewModel.setFadeInOutMs(it) },
        crossfadeDurationSec = crossfadeDurationSec,
        onSetCrossfadeDurationSec = { viewModel.setCrossfadeDurationSec(it) },
        ignoreAudioDuration = viewModel.ignoreAudioDuration.collectAsState().value,
        onSetIgnoreAudioDuration = { viewModel.setIgnoreAudioDuration(it) },
        fadeInMs = viewModel.fadeInMs.collectAsState().value,
        onSetFadeInMs = { viewModel.setFadeInMs(it) },
        fadeOutMs = viewModel.fadeOutMs.collectAsState().value,
        onSetFadeOutMs = { viewModel.setFadeOutMs(it) },
        shakeToSkip = shakeToSkip,
        onSetShakeToSkip = { viewModel.setShakeToSkip(it) },
        keepScreenAwake = keepScreenAwake,
        onSetKeepScreenAwake = { viewModel.setKeepScreenAwake(it) },
        audioBalance = audioBalance,
        onSetAudioBalance = { viewModel.setAudioBalance(it) },
        equalizerPreset = equalizerPreset,
        onSetEqualizerPreset = { viewModel.setEqualizerPreset(it) },
        lyricsAutoScroll = lyricsAutoScroll,
        onSetLyricsAutoScroll = { viewModel.setLyricsAutoScroll(it) },
        reduceVolumeOnNotification = reduceVolumeOnNotification,
        onSetReduceVolumeOnNotification = { viewModel.setReduceVolumeOnNotification(it) },
        pauseDuringCamera = !playDuringCamera,
        onSetPauseDuringCamera = { viewModel.setPlayDuringCamera(!it) },
        onAddFolder = { path, name, isExcluded -> viewModel.addFolder(path, name, isExcluded) },
        onRemoveFolder = { viewModel.removeFolder(it) },
        scrobbleThresholdType = viewModel.scrobbleThresholdType.collectAsState().value,
        onSetScrobbleThresholdType = { viewModel.setScrobbleThresholdType(it) },
        scrobbleThresholdValue = viewModel.scrobbleThresholdValue.collectAsState().value,
        onSetScrobbleThresholdValue = { viewModel.setScrobbleThresholdValue(it) },
        recentSearches = recentSearches,
        onDeleteRecentSearch = { viewModel.deleteRecentSearch(it) },
        onClearRecentSearches = { viewModel.clearRecentSearches() },
        onStartSleepTimer = { mins, finishLastSong -> viewModel.startSleepTimerMinutes(mins, finishLastSong) },
        onStartSongCountTimer = { viewModel.startSleepTimerSongs(it) },
        onAddSleepTimerMinutes = { viewModel.addSleepTimerMinutes(it) },
        onCancelSleepTimer = { viewModel.cancelSleepTimer() },
        onScanLibrary = { viewModel.scanLibrary() },
        onQuickScanAndCleanLibrary = { viewModel.quickScanAndCleanLibrary() },
        onBackClick = { viewModel.selectedTab.value = NavTab.HOME },
        highRefreshRate = viewModel.highRefreshRate.collectAsState().value,
        onSetHighRefreshRate = { viewModel.setHighRefreshRate(it) },
        alwaysShuffleMode = viewModel.alwaysShuffleMode.collectAsState().value,
        onSetAlwaysShuffleMode = { viewModel.setAlwaysShuffleMode(it) },
        gesturePlaybackEnabled = viewModel.gesturePlaybackEnabled.collectAsState().value,
        onSetGesturePlaybackEnabled = { viewModel.setGesturePlaybackEnabled(it) },
        homeSections = homeSections,
        onSetHomeSections = { viewModel.setHomeSections(it) },
        addToQueuePosition = viewModel.addToQueuePosition.collectAsState().value,
        onSetAddToQueuePosition = { viewModel.setAddToQueuePosition(it) },
        playlistAddSongPosition = viewModel.playlistAddSongPosition.collectAsState().value,
        onSetPlaylistAddSongPosition = { viewModel.setPlaylistAddSongPosition(it) },
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
        onToggleAutomaticBackup = { viewModel.setAutomaticBackupEnabled(it) },
        onSelectFrequency = { viewModel.setBackupFrequency(it) },
        onTriggerSync = { viewModel.triggerCloudSync() },
        onTriggerBackup = { viewModel.triggerBackupNow() },
        onSignOut = { viewModel.signOut() },
        onOpenAccount = { viewModel.openAccountScreen() },
        onOpenAuth = { viewModel.openAuthScreen() },
        initialTargetSection = viewModel.settingsTargetSection.collectAsState().value
    )
}
