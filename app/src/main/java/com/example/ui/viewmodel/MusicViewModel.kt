package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.FolderInfo
import com.example.data.model.Lyrics
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.data.model.WrappedData
import com.example.data.repository.MusicRepository
import com.example.scanner.LibraryScanner
import com.example.scanner.ScanProgress
import com.example.service.LyricsFetcher
import com.example.service.PlaybackManager
import com.example.service.PlaybackState
import com.example.service.SleepTimerManager
import com.example.service.SleepTimerState
import com.example.ui.components.NavTab
import com.example.util.TransliterationUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val scanner = LibraryScanner(application, db.songDao())
    val repository = MusicRepository(
        db.songDao(),
        db.folderDao(),
        db.playlistDao(),
        db.historyDao(),
        db.lyricsDao(),
        db.recentSearchDao(),
        db.smartPlaylistDao(),
        scanner
    )

    val preferences: UserPreferencesRepository = UserPreferencesRepository(application)
    val sleepTimerManager: SleepTimerManager = SleepTimerManager(
        scope = viewModelScope,
        isPlaybackActive = { playbackManager.player.isPlaying },
        onTimerExpired = { playbackManager.handleSleepTimerExpired() }
    )
    val playbackManager: PlaybackManager = PlaybackManager(application, viewModelScope, sleepTimerManager)
    val lyricsFetcher: LyricsFetcher = LyricsFetcher(db.lyricsDao(), application)
    val audioRouteManager: com.example.util.AudioRouteManager = com.example.util.AudioRouteManager(application)
    val currentAudioRoute: StateFlow<com.example.util.AudioRouteInfo> = audioRouteManager.currentRoute
    val availableAudioDevices: StateFlow<List<com.example.util.AudioRouteDevice>> = audioRouteManager.availableDevices

    fun selectAudioRoute(device: com.example.util.AudioRouteDevice) {
        audioRouteManager.selectRoute(device)
        playbackManager.setPreferredAudioDevice(device.rawDeviceInfo)
    }
    fun launchSystemOutputSwitcher(context: android.content.Context) = audioRouteManager.launchSystemOutputSwitcher(context)

    // UI Navigation State
    val selectedTab = MutableStateFlow(NavTab.HOME)
    val settingsTargetSection = MutableStateFlow<String?>(null)
    val showNowPlaying = MutableStateFlow(false)
    val searchTabQuery = MutableStateFlow("")
    val librarySearchQuery = MutableStateFlow("")
    val searchQuery: MutableStateFlow<String> get() = searchTabQuery
    val selectedPlaylist = MutableStateFlow<Playlist?>(null)
    val selectedSmartPlaylist = MutableStateFlow<com.example.data.model.SmartPlaylist?>(null)
    val showSplash = MutableStateFlow(true)

    fun openSettings(section: String? = null) {
        settingsTargetSection.value = section
        selectedTab.value = NavTab.SETTINGS
    }

    fun dismissSplash() {
        showSplash.value = false
    }

    // Data Flows
    val ignoreAudioDuration: StateFlow<Int> = preferences.ignoreAudioDuration
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val allSongs: StateFlow<List<Song>> = repository.allSongs.combine(ignoreAudioDuration) { list, minSec ->
        if (minSec > 0) {
            list.filter { it.durationMs >= minSec * 1000L }
        } else {
            list
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val favoriteSongs: StateFlow<List<Song>> = repository.favoriteSongs.combine(ignoreAudioDuration) { list, minSec ->
        if (minSec > 0) list.filter { it.durationMs >= minSec * 1000L } else list
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val recentlyAddedSongs: StateFlow<List<Song>> = repository.recentlyAddedSongs.combine(ignoreAudioDuration) { list, minSec ->
        if (minSec > 0) list.filter { it.durationMs >= minSec * 1000L } else list
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val recentlyPlayedSongs: StateFlow<List<Song>> = repository.recentlyPlayedSongs.combine(ignoreAudioDuration) { list, minSec ->
        if (minSec > 0) list.filter { it.durationMs >= minSec * 1000L } else list
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val mostPlayedSongs: StateFlow<List<Song>> = repository.mostPlayedSongs.combine(ignoreAudioDuration) { list, minSec ->
        if (minSec > 0) list.filter { it.durationMs >= minSec * 1000L } else list
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allFolders: StateFlow<List<FolderInfo>> = repository.allFolders.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allPlaylists: StateFlow<List<Playlist>> = repository.allPlaylists.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val mostPlayedPlaylists: StateFlow<List<Playlist>> = repository.mostPlayedPlaylists.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val smartPlaylists: StateFlow<List<com.example.data.model.SmartPlaylist>> = repository.smartPlaylists.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentSearches: StateFlow<List<String>> = repository.recentSearches.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val scanProgress: StateFlow<ScanProgress> = repository.scanProgress.stateIn(viewModelScope, SharingStarted.Lazily, ScanProgress())
    val playbackState: StateFlow<PlaybackState> = playbackManager.playbackState
    val playbackPositionMs: StateFlow<Long> = playbackManager.playbackPositionMs
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimerManager.state
    val isOnboardingCompleted: StateFlow<Boolean> = preferences.isOnboardingCompleted.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val themeMode: StateFlow<String> = preferences.themeMode.stateIn(viewModelScope, SharingStarted.Lazily, "DARK")
    val uiTheme: StateFlow<String> = preferences.uiTheme.stateIn(viewModelScope, SharingStarted.Lazily, "MIDNIGHT_OBSIDIAN")
    val homeScreenLayout: StateFlow<String> = preferences.homeScreenLayout.stateIn(viewModelScope, SharingStarted.Lazily, "BENTO_DYNAMIC")
    val nowPlayingLayout: StateFlow<String> = preferences.nowPlayingLayout.stateIn(viewModelScope, SharingStarted.Lazily, "MODERN_BENTO")
    val playlistsLayout: StateFlow<String> = preferences.playlistsLayout.stateIn(viewModelScope, SharingStarted.Lazily, "BENTO_GRID")
    val librarySortOption: StateFlow<String> = preferences.librarySortOption.stateIn(viewModelScope, SharingStarted.Lazily, "RECENTLY_ADDED")
    val librarySortAscending: StateFlow<Boolean> = preferences.librarySortAscending.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val playlistViewMode: StateFlow<String> = preferences.playlistViewMode.stateIn(viewModelScope, SharingStarted.Lazily, "GRID")
    val waveformStyle: StateFlow<String> = preferences.waveformStyle.stateIn(viewModelScope, SharingStarted.Lazily, "SNAKE")
    val amoledMode: StateFlow<Boolean> = preferences.amoledMode.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val accentColorPreset: StateFlow<String> = preferences.accentColorPreset.stateIn(viewModelScope, SharingStarted.Lazily, "PURPLE")
    val customAccentColor: StateFlow<Int> = preferences.customAccentColor.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val isDynamicArtworkTheme: StateFlow<Boolean> = preferences.isDynamicArtworkTheme.stateIn(viewModelScope, SharingStarted.Lazily, true)

    private val _dynamicArtworkColor = MutableStateFlow<androidx.compose.ui.graphics.Color?>(null)
    val dynamicArtworkColor: StateFlow<androidx.compose.ui.graphics.Color?> = _dynamicArtworkColor.asStateFlow()

    val cornerRadiusDp: StateFlow<Int> = preferences.cornerRadiusDp.stateIn(viewModelScope, SharingStarted.Lazily, 16)
    val fontSizeScale: StateFlow<String> = preferences.fontSizeScale.stateIn(viewModelScope, SharingStarted.Lazily, "MEDIUM")
    val artworkSize: StateFlow<String> = preferences.artworkSize.stateIn(viewModelScope, SharingStarted.Lazily, "MEDIUM")
    val showArtist: StateFlow<Boolean> = preferences.showArtist.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val showAlbum: StateFlow<Boolean> = preferences.showAlbum.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val showAudioOutput: StateFlow<Boolean> = preferences.showAudioOutput.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val miniPlayerStyle: StateFlow<String> = preferences.miniPlayerStyle.stateIn(viewModelScope, SharingStarted.Lazily, "FLOATING")
    val miniPlayerShowPrevious: StateFlow<Boolean> = preferences.miniPlayerShowPrevious.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val miniPlayerShowNext: StateFlow<Boolean> = preferences.miniPlayerShowNext.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val miniPlayerShowFavorite: StateFlow<Boolean> = preferences.miniPlayerShowFavorite.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val gaplessPlayback: StateFlow<Boolean> = preferences.gaplessPlayback.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val normalizeAudio: StateFlow<Boolean> = preferences.normalizeAudio.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoPlayOnHeadphoneConnection: StateFlow<Boolean> = preferences.autoPlayOnHeadphoneConnection.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val addToQueuePosition: StateFlow<String> = preferences.addToQueuePosition.stateIn(viewModelScope, SharingStarted.Lazily, "BOTTOM")
    val playlistAddSongPosition: StateFlow<String> = preferences.playlistAddSongPosition.stateIn(viewModelScope, SharingStarted.Lazily, "BOTTOM")
    val crossfadeDurationSec: StateFlow<Int> = preferences.crossfadeDurationSec.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val lyricsAutoScroll: StateFlow<Boolean> = preferences.lyricsAutoScroll.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val lyricsFontSize: StateFlow<String> = preferences.lyricsFontSize.stateIn(viewModelScope, SharingStarted.Lazily, "MEDIUM")
    val notificationEnabled: StateFlow<Boolean> = preferences.notificationEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val pauseOnZeroVolume: StateFlow<Boolean> = preferences.pauseOnZeroVolume.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoResumeOnVolumeIncrease: StateFlow<Boolean> = preferences.autoResumeOnVolumeIncrease.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val fadeInOutMs: StateFlow<Int> = preferences.fadeInOutMs.stateIn(viewModelScope, SharingStarted.Lazily, 1000)
    val fadeInMs: StateFlow<Int> = preferences.fadeInMs.stateIn(viewModelScope, SharingStarted.Lazily, 1000)
    val fadeOutMs: StateFlow<Int> = preferences.fadeOutMs.stateIn(viewModelScope, SharingStarted.Lazily, 1000)
    val shakeToSkip: StateFlow<Boolean> = preferences.shakeToSkip.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val keepScreenAwake: StateFlow<Boolean> = preferences.keepScreenAwake.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val audioBalance: StateFlow<Float> = preferences.audioBalance.stateIn(viewModelScope, SharingStarted.Lazily, 0f)
    val equalizerPreset: StateFlow<String> = preferences.equalizerPreset.stateIn(viewModelScope, SharingStarted.Lazily, "NORMAL")
    val reduceVolumeOnNotification: StateFlow<Boolean> = preferences.reduceVolumeOnNotification.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val scrobbleThresholdType: StateFlow<String> = preferences.scrobbleThresholdType.stateIn(viewModelScope, SharingStarted.Lazily, "SECONDS")
    val scrobbleThresholdValue: StateFlow<Int> = preferences.scrobbleThresholdValue.stateIn(viewModelScope, SharingStarted.Lazily, 30)
    val highRefreshRate: StateFlow<Boolean> = preferences.highRefreshRate.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val alwaysShuffleMode: StateFlow<Boolean> = preferences.alwaysShuffleMode.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val gesturePlaybackEnabled: StateFlow<Boolean> = preferences.gesturePlaybackEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val fetchOnlineArtworkHighRes: StateFlow<Boolean> = preferences.fetchOnlineArtworkHighRes.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val cacheLyricsEnabled: StateFlow<Boolean> = preferences.cacheLyricsEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val cacheAlbumArtEnabled: StateFlow<Boolean> = preferences.cacheAlbumArtEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val cacheSongArtWorkEnabled: StateFlow<Boolean> = preferences.cacheSongArtWorkEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val customEqBands: StateFlow<List<Float>> = preferences.customEqBands.stateIn(viewModelScope, SharingStarted.Lazily, listOf(0f, 0f, 0f, 0f, 0f))
    val customBassBoost: StateFlow<Int> = preferences.customBassBoost.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val customVirtualizer: StateFlow<Int> = preferences.customVirtualizer.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val homeSections: StateFlow<List<String>> = preferences.homeSections.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        listOf("speed_dial", "continue_listening", "quick_actions", "listening_stats", "most_played", "most_played_playlists", "recently_played", "recently_added", "top_artists", "top_albums")
    )

    private val _speedDialSongs = MutableStateFlow<List<Song>>(emptyList())
    val speedDialSongs: StateFlow<List<Song>> = _speedDialSongs.asStateFlow()

    fun refreshSpeedDial() {
        val songs = allSongs.value
        if (songs.isNotEmpty()) {
            _speedDialSongs.value = songs.shuffled().take(9)
        }
    }

    fun setHomeSections(sections: List<String>) = viewModelScope.launch {
        preferences.setHomeSections(sections)
    }

    val equalizerManager get() = playbackManager.equalizerManager

    init {
        audioRouteManager.onDeviceSelected = { dev ->
            playbackManager.setPreferredAudioDevice(dev)
        }
        viewModelScope.launch {
            allSongs.collect { songs ->
                if (_speedDialSongs.value.isEmpty() && songs.isNotEmpty()) {
                    _speedDialSongs.value = songs.shuffled().take(9)
                }
            }
        }
        playbackManager.onFavoriteToggleFromNotification = {
            playbackState.value.currentSong?.let { toggleFavorite(it) }
        }
        viewModelScope.launch {
            alwaysShuffleMode.collect {
                playbackManager.alwaysShuffleMode = it
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            playbackState
                .map { it.currentSong?.id to it.currentSong?.artworkUri }
                .distinctUntilChanged()
                .collect { (songId, artUri) ->
                    if (songId != null && !artUri.isNullOrBlank()) {
                        val extracted = com.example.util.ArtworkColorExtractor.extractPrimaryColor(application, artUri)
                        if (extracted != null) {
                            _dynamicArtworkColor.value = extracted
                        }
                    } else if (songId == null) {
                        _dynamicArtworkColor.value = null
                    }
                }
        }
        viewModelScope.launch {
            autoPlayOnHeadphoneConnection.collect { enabled ->
                playbackManager.autoPlayOnHeadphoneConnection = enabled
            }
        }
        viewModelScope.launch {
            fadeInMs.collect { ms ->
                playbackManager.fadeInMs = ms
            }
        }
        viewModelScope.launch {
            fadeOutMs.collect { ms ->
                playbackManager.fadeOutMs = ms
            }
        }
        viewModelScope.launch {
            crossfadeDurationSec.collect { sec ->
                playbackManager.crossfadeDurationSec = sec
            }
        }
        viewModelScope.launch {
            gaplessPlayback.collect { enabled ->
                playbackManager.gaplessPlayback = enabled
            }
        }
        viewModelScope.launch {
            normalizeAudio.collect { enabled ->
                playbackManager.isNormalizeAudioEnabled = enabled
            }
        }
        viewModelScope.launch {
            pauseOnZeroVolume.collect { enabled ->
                playbackManager.pauseOnZeroVolume = enabled
            }
        }
        viewModelScope.launch {
            autoResumeOnVolumeIncrease.collect { enabled ->
                playbackManager.autoResumeOnVolumeIncrease = enabled
            }
        }
        viewModelScope.launch {
            reduceVolumeOnNotification.collect { enabled ->
                playbackManager.reduceVolumeOnNotification = enabled
            }
        }
        viewModelScope.launch {
            audioBalance.collect { balance ->
                playbackManager.setAudioBalance(balance)
            }
        }
        viewModelScope.launch {
            equalizerPreset.collect { preset ->
                playbackManager.equalizerManager.applyPreset(preset)
            }
        }
        viewModelScope.launch {
            scrobbleThresholdType.collect {
                playbackManager.scrobbleThresholdType = it
            }
        }
        viewModelScope.launch {
            scrobbleThresholdValue.collect {
                playbackManager.scrobbleThresholdValue = it
            }
        }
        viewModelScope.launch {
            val currentUiTheme = preferences.uiTheme.firstOrNull() ?: "MIDNIGHT_OBSIDIAN"
            val currentTheme = com.example.ui.theme.AppUiTheme.fromId(currentUiTheme)
            val currentMode = (preferences.themeMode.firstOrNull() ?: "DARK").uppercase()
            if (currentMode !in currentTheme.supportedModes) {
                preferences.setThemeMode(currentTheme.defaultMode)
            }
        }
    }

    fun setThemeMode(mode: String) = viewModelScope.launch {
        val selectedTheme = com.example.ui.theme.AppUiTheme.fromId(uiTheme.value)
        val targetMode = mode.uppercase()
        if (selectedTheme.supportedModes.contains(targetMode)) {
            preferences.setThemeMode(targetMode)
        }
    }

    fun setUiTheme(themeId: String) = viewModelScope.launch {
        preferences.setUiTheme(themeId)
        val selectedTheme = com.example.ui.theme.AppUiTheme.fromId(themeId)
        val currentMode = themeMode.value.uppercase()
        if (currentMode !in selectedTheme.supportedModes) {
            preferences.setThemeMode(selectedTheme.defaultMode)
        }
    }
    fun setHomeScreenLayout(layoutId: String) = viewModelScope.launch { preferences.setHomeScreenLayout(layoutId) }
    fun setNowPlayingLayout(layoutId: String) = viewModelScope.launch { 
        preferences.setNowPlayingLayout(layoutId)
        val defaultWaveform = when (com.example.ui.theme.NowPlayingLayout.fromId(layoutId)) {
            com.example.ui.theme.NowPlayingLayout.MODERN_BENTO -> "SLEEK_LINE"
            com.example.ui.theme.NowPlayingLayout.NEON_WAVEFORM -> "BARS"
            com.example.ui.theme.NowPlayingLayout.VINYL_TURNTABLE -> "SNAKE"
            com.example.ui.theme.NowPlayingLayout.MINIMAL_GLASS -> "SLEEK_LINE"
        }
        preferences.setWaveformStyle(defaultWaveform)
    }
    fun setPlaylistsLayout(layoutId: String) = viewModelScope.launch { preferences.setPlaylistsLayout(layoutId) }
    fun setWaveformStyle(style: String) = viewModelScope.launch { preferences.setWaveformStyle(style) }
    fun setAmoledMode(enabled: Boolean) = viewModelScope.launch { preferences.setAmoledMode(enabled) }
    fun setAccentColorPreset(preset: String) = viewModelScope.launch { preferences.setAccentColorPreset(preset) }
    fun setCustomAccentColor(colorInt: Int) = viewModelScope.launch { preferences.setCustomAccentColor(colorInt) }
    fun setDynamicArtworkTheme(enabled: Boolean) = viewModelScope.launch { preferences.setDynamicArtworkTheme(enabled) }
    fun setCornerRadiusDp(radius: Int) = viewModelScope.launch { preferences.setCornerRadiusDp(radius) }
    fun setFontSizeScale(scale: String) = viewModelScope.launch { preferences.setFontSizeScale(scale) }
    fun setArtworkSize(size: String) = viewModelScope.launch { preferences.setArtworkSize(size) }
    fun setShowArtist(show: Boolean) = viewModelScope.launch { preferences.setShowArtist(show) }
    fun setShowAlbum(show: Boolean) = viewModelScope.launch { preferences.setShowAlbum(show) }
    fun setShowAudioOutput(show: Boolean) = viewModelScope.launch { preferences.setShowAudioOutput(show) }
    fun setMiniPlayerStyle(style: String) = viewModelScope.launch { preferences.setMiniPlayerStyle(style) }
    fun setMiniPlayerShowPrevious(show: Boolean) = viewModelScope.launch { preferences.setMiniPlayerShowPrevious(show) }
    fun setMiniPlayerShowNext(show: Boolean) = viewModelScope.launch { preferences.setMiniPlayerShowNext(show) }
    fun setMiniPlayerShowFavorite(show: Boolean) = viewModelScope.launch { preferences.setMiniPlayerShowFavorite(show) }
    fun setAddToQueuePosition(position: String) {
        viewModelScope.launch { preferences.setAddToQueuePosition(position) }
        playbackManager.addToQueuePosition = position
    }
    fun setPlaylistAddSongPosition(position: String) = viewModelScope.launch { preferences.setPlaylistAddSongPosition(position) }
    fun setGaplessPlayback(enabled: Boolean) = viewModelScope.launch { preferences.setGaplessPlayback(enabled) }
    fun setCrossfadeDurationSec(sec: Int) = viewModelScope.launch { preferences.setCrossfadeDurationSec(sec) }
    fun setLyricsAutoScroll(enabled: Boolean) = viewModelScope.launch { preferences.setLyricsAutoScroll(enabled) }
    fun setGesturePlaybackEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setGesturePlaybackEnabled(enabled) }
    fun getLyricsOffsetMs(songId: Long): Long = preferences.getLyricsSyncOffsetMs(songId)
    fun setLyricsOffsetMs(songId: Long, offsetMs: Long) {
        preferences.setLyricsSyncOffsetMs(songId, offsetMs)
    }
    fun setLyricsFontSize(size: String) = viewModelScope.launch { preferences.setLyricsFontSize(size) }
    fun setNotificationEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setNotificationEnabled(enabled) }
    fun setAutoPlayOnHeadphoneConnection(enabled: Boolean) = viewModelScope.launch { preferences.setAutoPlayOnHeadphoneConnection(enabled) }
    fun setPauseOnZeroVolume(enabled: Boolean) = viewModelScope.launch { preferences.setPauseOnZeroVolume(enabled) }
    fun setAutoResumeOnVolumeIncrease(enabled: Boolean) = viewModelScope.launch { preferences.setAutoResumeOnVolumeIncrease(enabled) }
    private var ignoreDurationJob: kotlinx.coroutines.Job? = null
    fun setIgnoreAudioDuration(sec: Int) {
        val safeSec = sec.coerceIn(0, 300)
        ignoreDurationJob?.cancel()
        ignoreDurationJob = viewModelScope.launch(Dispatchers.IO) {
            preferences.setIgnoreAudioDuration(safeSec)
            kotlinx.coroutines.delay(500)
            repository.quickScanAndCleanLibrary(safeSec * 1000L)
        }
    }
    fun setFadeInOutMs(ms: Int) = viewModelScope.launch { preferences.setFadeInOutMs(ms) }
    fun setNormalizeAudio(enabled: Boolean) = viewModelScope.launch { preferences.setNormalizeAudio(enabled) }
    fun setFadeInMs(ms: Int) = viewModelScope.launch { preferences.setFadeInMs(ms) }
    fun setFadeOutMs(ms: Int) = viewModelScope.launch { preferences.setFadeOutMs(ms) }
    fun setShakeToSkip(enabled: Boolean) = viewModelScope.launch { preferences.setShakeToSkip(enabled) }
    fun setKeepScreenAwake(enabled: Boolean) = viewModelScope.launch { preferences.setKeepScreenAwake(enabled) }
    fun setAudioBalance(balance: Float) = viewModelScope.launch { preferences.setAudioBalance(balance) }
    fun setEqualizerPreset(preset: String) = viewModelScope.launch {
        preferences.setEqualizerPreset(preset)
        val matchedPreset = com.example.ui.components.EQ_PRESETS.find { it.id.equals(preset, ignoreCase = true) }
        if (matchedPreset != null && !preset.equals("CUSTOM", ignoreCase = true)) {
            preferences.setCustomEqBands(matchedPreset.bands, markAsCustom = false)
            preferences.setCustomBassBoost(matchedPreset.bassBoost, markAsCustom = false)
            preferences.setCustomVirtualizer(matchedPreset.virtualizer, markAsCustom = false)
        }
        playbackManager.equalizerManager.applyPreset(preset)
    }
    fun setReduceVolumeOnNotification(enabled: Boolean) = viewModelScope.launch { preferences.setReduceVolumeOnNotification(enabled) }
    fun setScrobbleThresholdType(type: String) = viewModelScope.launch { preferences.setScrobbleThresholdType(type) }
    fun setScrobbleThresholdValue(value: Int) = viewModelScope.launch { preferences.setScrobbleThresholdValue(value) }
    fun setHighRefreshRate(enabled: Boolean) = viewModelScope.launch { preferences.setHighRefreshRate(enabled) }
    fun setAlwaysShuffleMode(enabled: Boolean) = viewModelScope.launch { preferences.setAlwaysShuffleMode(enabled) }
    fun setFetchOnlineArtworkHighRes(enabled: Boolean) = viewModelScope.launch { preferences.setFetchOnlineArtworkHighRes(enabled) }
    fun setCacheLyricsEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setCacheLyricsEnabled(enabled) }
    fun setCacheAlbumArtEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setCacheAlbumArtEnabled(enabled) }
    fun setCacheSongArtWorkEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setCacheSongArtWorkEnabled(enabled) }

    fun setCustomEqualizerBands(bands: List<Float>) = viewModelScope.launch {
        preferences.setCustomEqBands(bands)
        playbackManager.equalizerManager.setCustomBands(bands, customBassBoost.value, customVirtualizer.value)
    }

    fun setCustomEqBands(bands: List<Float>) = setCustomEqualizerBands(bands)

    fun setCustomBassBoost(strength: Int) = viewModelScope.launch {
        preferences.setCustomBassBoost(strength)
        playbackManager.equalizerManager.setBassBoostStrength(strength)
    }

    fun setCustomVirtualizer(strength: Int) = viewModelScope.launch {
        preferences.setCustomVirtualizer(strength)
        playbackManager.equalizerManager.setVirtualizerStrength(strength)
    }

    private val _lyricsCacheSize = MutableStateFlow("0.0 KB")
    val lyricsCacheSize: StateFlow<String> = _lyricsCacheSize.asStateFlow()

    private val _albumArtCacheSize = MutableStateFlow("0.0 KB")
    val albumArtCacheSize: StateFlow<String> = _albumArtCacheSize.asStateFlow()

    private val _songArtCacheSize = MutableStateFlow("0.0 KB")
    val songArtCacheSize: StateFlow<String> = _songArtCacheSize.asStateFlow()

    private val _totalCacheSize = MutableStateFlow("0.0 KB")
    val totalCacheSize: StateFlow<String> = _totalCacheSize.asStateFlow()

    fun refreshCacheSizes() = viewModelScope.launch(Dispatchers.IO) {
        val lSize = preferences.getLyricsCacheSizeBytes()
        val aSize = preferences.getAlbumArtCacheSizeBytes()
        val sSize = preferences.getSongArtCacheSizeBytes()
        val tSize = preferences.getTotalCacheSizeBytes()
        withContext(Dispatchers.Main) {
            _lyricsCacheSize.value = com.example.data.local.UserPreferencesRepository.formatFileSize(lSize)
            _albumArtCacheSize.value = com.example.data.local.UserPreferencesRepository.formatFileSize(aSize)
            _songArtCacheSize.value = com.example.data.local.UserPreferencesRepository.formatFileSize(sSize)
            _totalCacheSize.value = com.example.data.local.UserPreferencesRepository.formatFileSize(tSize)
        }
    }

    fun clearLyricsCache(onComplete: () -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        db.lyricsDao().clearLyricsCache()
        preferences.clearLyricsCache()
        refreshCacheSizes()
        withContext(Dispatchers.Main) { onComplete() }
    }

    fun clearAlbumArtCache(onComplete: () -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        preferences.clearAlbumArtCache()
        refreshCacheSizes()
        withContext(Dispatchers.Main) { onComplete() }
    }

    fun clearSongArtCache(onComplete: () -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        preferences.clearSongArtCache()
        refreshCacheSizes()
        withContext(Dispatchers.Main) { onComplete() }
    }

    fun clearAllCaches(onComplete: () -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        db.lyricsDao().clearLyricsCache()
        preferences.clearLyricsCache()
        preferences.clearAlbumArtCache()
        preferences.clearSongArtCache()
        refreshCacheSizes()
        refreshDatabaseStats()
        withContext(Dispatchers.Main) { onComplete() }
    }

    val databaseStorageTier: StateFlow<String> = preferences.databaseStorageTier.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "EXPANDED"
    )

    private val _databaseStorageStats = MutableStateFlow<com.example.data.local.DatabaseStorageStats?>(null)
    val databaseStorageStats: StateFlow<com.example.data.local.DatabaseStorageStats?> = _databaseStorageStats.asStateFlow()

    private val _isOptimizingDatabase = MutableStateFlow(false)
    val isOptimizingDatabase: StateFlow<Boolean> = _isOptimizingDatabase.asStateFlow()

    fun setDatabaseStorageTier(tier: String) = viewModelScope.launch {
        preferences.setDatabaseStorageTier(tier)
    }

    fun refreshDatabaseStats() = viewModelScope.launch(Dispatchers.IO) {
        val stats = com.example.data.local.DatabaseStorageManager.getStorageStats(getApplication())
        withContext(Dispatchers.Main) {
            _databaseStorageStats.value = stats
        }
    }

    fun optimizeDatabase(onComplete: (reclaimedBytes: Long, message: String) -> Unit = { _, _ -> }) = viewModelScope.launch {
        _isOptimizingDatabase.value = true
        val result = withContext(Dispatchers.IO) {
            com.example.data.local.DatabaseStorageManager.optimizeDatabase(getApplication())
        }
        refreshDatabaseStats()
        refreshCacheSizes()
        _isOptimizingDatabase.value = false
        onComplete(result.bytesReclaimed, result.message)
    }

    fun exportDatabaseBackup(onComplete: (java.io.File?) -> Unit) = viewModelScope.launch {
        val file = withContext(Dispatchers.IO) {
            com.example.data.local.DatabaseStorageManager.exportDatabaseBackup(getApplication())
        }
        onComplete(file)
    }

    fun importDatabaseBackup(jsonString: String, onComplete: (Boolean, String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            com.example.data.local.DatabaseStorageManager.importDatabaseBackup(getApplication(), jsonString)
        }
        refreshDatabaseStats()
        onComplete(result.success, result.message)
    }

    fun updatePlaylistViewMode(mode: String) {
        viewModelScope.launch {
            preferences.setPlaylistViewMode(mode)
        }
    }

    fun updateLibrarySort(option: String, ascending: Boolean) {
        viewModelScope.launch {
            preferences.saveLibrarySortOption(option, ascending)
        }
    }

    // Lyrics State
    private val _currentLyrics = MutableStateFlow<Lyrics?>(null)
    val currentLyrics: StateFlow<Lyrics?> = _currentLyrics.asStateFlow()

    init {
        refreshCacheSizes()
        refreshDatabaseStats()

        viewModelScope.launch {
            repository.checkAndSeedInitialData()
        }

        viewModelScope.launch {
            scrobbleThresholdType.collect {
                playbackManager.scrobbleThresholdType = it
            }
        }
        viewModelScope.launch {
            scrobbleThresholdValue.collect {
                playbackManager.scrobbleThresholdValue = it
            }
        }

        // Restore playback state once songs are loaded
        viewModelScope.launch {
            val loadedSongs = allSongs.filter { it.isNotEmpty() }.first()
            val savedRepeat = preferences.repeatMode.first()
            if (savedRepeat != 0) {
                playbackManager.setRepeatMode(savedRepeat)
            }

            if (loadedSongs.isNotEmpty()) {
                val lastQueueIds = preferences.lastQueueIds.first()
                val lastSongId = preferences.lastSongId.first()
                val lastPos = preferences.lastPosition.first()
                val lastDuration = preferences.lastDuration.first()
                val lastIdx = preferences.lastQueueIndex.first()

                val songsMap = loadedSongs.associateBy { it.id }
                val restoredQueue = if (lastQueueIds.isNotEmpty()) {
                    lastQueueIds.mapNotNull { songsMap[it] }
                } else emptyList()

                val finalQueue = if (restoredQueue.isNotEmpty()) restoredQueue else loadedSongs
                val finalIndex = if (lastSongId > 0) {
                    val idx = finalQueue.indexOfFirst { it.id == lastSongId }
                    if (idx >= 0) idx else lastIdx.coerceIn(finalQueue.indices)
                } else {
                    lastIdx.coerceIn(finalQueue.indices)
                }

                val currentSongToRestore = finalQueue.getOrNull(finalIndex)
                val reliableDuration = if (currentSongToRestore != null && currentSongToRestore.durationMs > 0L) currentSongToRestore.durationMs else lastDuration
                val queueWithCorrectDuration = if (currentSongToRestore != null && currentSongToRestore.durationMs <= 0L && reliableDuration > 0L) {
                    finalQueue.toMutableList().apply {
                        this[finalIndex] = currentSongToRestore.copy(durationMs = reliableDuration)
                    }
                } else finalQueue

                playbackManager.restorePlaybackState(queueWithCorrectDuration, finalIndex, lastPos.coerceAtLeast(0L), reliableDuration)
            }
        }

        // Automatically fetch lyrics when track changes and persist state
        playbackManager.onUpdatePlaybackSession = { sessionId, trackId, startedAt, endedAt, listenedDurationMs, sourceContext, totalDurationMs ->
            viewModelScope.launch {
                val currentType = scrobbleThresholdType.value
                val currentValue = scrobbleThresholdValue.value
                repository.recordPlaybackSession(
                    sessionId = sessionId,
                    trackId = trackId,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    listenedDurationMs = listenedDurationMs,
                    sourceContext = sourceContext,
                    totalTrackDurationMs = totalDurationMs,
                    scrobbleThresholdType = currentType,
                    scrobbleThresholdValue = currentValue
                )
            }
        }

        var lastFetchedSongId: Long? = null
        var lyricsFetchJob: kotlinx.coroutines.Job? = null
        var lastSavedSongId: Long = -1L
        var lastSavedPos: Long = -1L
        var lastSaveTime: Long = 0L

        viewModelScope.launch {
            playbackState.collect { state ->
                val song = state.currentSong
                if (song != null) {
                    if (song.id != lastFetchedSongId) {
                        lastFetchedSongId = song.id
                        lyricsFetchJob?.cancel()
                        // Reset search lyrics state for new track so old candidate results don't leak to next/prev song
                        lyricsSearchResults.value = emptyList()
                        lastLyricsSearchSongId = null
                        lastLyricsSearchQuery = ""
                        cachedLyricsCandidates = emptyList()
                        // Instantly reset _currentLyrics to loading state for this specific song ID
                        _currentLyrics.value = com.example.data.model.Lyrics(
                            songId = song.id,
                            plainLyrics = null,
                            syncedLyricsJson = null,
                            isCached = false,
                            source = "loading"
                        )
                        lyricsFetchJob = viewModelScope.launch(Dispatchers.IO) {
                            val fetched = lyricsFetcher.getOrFetchLyrics(song)
                            if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && playbackState.value.currentSong?.id == song.id) {
                                _currentLyrics.value = fetched
                                refreshCacheSizes()
                            }
                            try {
                                if (song.artworkUri.isNullOrBlank()) {
                                    val resolvedUri = com.example.util.ArtworkManager.getOrExtractArtworkUri(getApplication(), song.id, song.path, song.albumId)
                                    if (!resolvedUri.isNullOrBlank()) {
                                        repository.updateSongArtwork(song.id, resolvedUri)
                                        playbackManager.updateCurrentSongArtwork(song.id, resolvedUri)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    // Throttle DataStore writes to prevent thread starvation and ANRs
                    val now = System.currentTimeMillis()
                    val isTrackChange = song.id != lastSavedSongId
                    val isStatePaused = !state.isPlaying
                    val isThrottledTime = (now - lastSaveTime > 10000L) && kotlin.math.abs(state.positionMs - lastSavedPos) > 3000L

                    if (isTrackChange || isStatePaused || isThrottledTime) {
                        lastSavedSongId = song.id
                        lastSavedPos = state.positionMs
                        lastSaveTime = now
                        viewModelScope.launch(Dispatchers.IO) {
                            preferences.saveLastPlaybackState(
                                songId = song.id,
                                position = state.positionMs,
                                queueIds = state.queue.map { it.id },
                                queueIndex = state.queueIndex
                            )
                        }
                    }
                } else {
                    lyricsFetchJob?.cancel()
                    _currentLyrics.value = null
                    lastFetchedSongId = null
                }
            }
        }
    }

    private var scanJob: kotlinx.coroutines.Job? = null
    fun scanLibrary() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            repository.scanLibrary(ignoreAudioDuration.value * 1000L)
        }
    }

    private var quickScanJob: kotlinx.coroutines.Job? = null
    fun quickScanAndCleanLibrary() {
        if (quickScanJob?.isActive == true || scanJob?.isActive == true) return
        quickScanJob = viewModelScope.launch(Dispatchers.IO) {
            repository.quickScanAndCleanLibrary(ignoreAudioDuration.value * 1000L)
        }
    }

    fun addRecentSearch(query: String) {
        viewModelScope.launch {
            repository.addRecentSearch(query)
        }
    }

    fun deleteRecentSearch(query: String) {
        viewModelScope.launch {
            repository.deleteRecentSearch(query)
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            repository.clearRecentSearches()
        }
    }

    fun playSong(song: Song, queue: List<Song> = allSongs.value, sourceContext: String = "UNKNOWN") {
        val index = queue.indexOf(song).coerceAtLeast(0)
        playbackManager.playQueue(queue, index, sourceContext)
    }

    fun playAll(songs: List<Song>, sourceContext: String = "UNKNOWN") {
        if (songs.isNotEmpty()) {
            playbackManager.playQueue(
                songs = songs,
                startIndex = 0,
                sourceContext = sourceContext,
                forceShuffle = false
            )
        }
    }

    fun deleteSong(song: Song, context: android.content.Context, onResult: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            val (success, errorMsg) = repository.deleteSong(song, context)
            if (success) {
                playbackManager.removeSongFromQueueById(song.id)
                android.widget.Toast.makeText(context, "Deleted successfully", android.widget.Toast.LENGTH_SHORT).show()
                onResult?.invoke(true, null)
            } else {
                android.widget.Toast.makeText(context, errorMsg ?: "Failed to delete file from device", android.widget.Toast.LENGTH_LONG).show()
                onResult?.invoke(false, errorMsg)
            }
        }
    }

    fun togglePlayPause() = playbackManager.togglePlayPause()
    fun skipToNext() = playbackManager.skipToNext()
    fun skipToPrevious() = playbackManager.skipToPrevious()
    fun seekTo(positionMs: Long) = playbackManager.seekTo(positionMs)
    fun toggleShuffle(): String = playbackManager.toggleShuffle()
    fun shuffleAndPlay(songs: List<Song> = allSongs.value, sourceContext: String = "LIBRARY") {
        if (songs.isNotEmpty()) {
            playbackManager.playQueue(songs, startIndex = 0, sourceContext = sourceContext, forceShuffle = true)
        }
    }
    fun toggleRepeat() = playbackManager.toggleRepeat()

    fun reorderQueue(fromIndex: Int, toIndex: Int) = playbackManager.reorderQueue(fromIndex, toIndex)
    fun updateQueueOrder(newQueue: List<Song>) = playbackManager.updateQueueOrder(newQueue)
    fun removeFromQueue(index: Int) = playbackManager.removeFromQueue(index)
    fun removeDuplicateSongsFromQueue(): Int = playbackManager.removeDuplicatesFromQueue()
    fun jumpToQueueIndex(index: Int) = playbackManager.jumpToQueueIndex(index)
    fun playNext(song: Song) = playbackManager.playNext(song)
    fun addToQueue(song: Song) = playbackManager.addToQueue(song)

    fun clearRecentlyPlayed() {
        viewModelScope.launch {
            repository.clearRecentlyPlayedHistory()
        }
    }

    fun updatePlaylistArtwork(playlistId: Long, artworkUri: String?) {
        viewModelScope.launch {
            val persistentUri = if (artworkUri != null) {
                com.example.util.ArtworkManager.persistPlaylistCover(getApplication(), playlistId, artworkUri)
            } else {
                null
            }
            repository.updatePlaylistArtwork(playlistId, persistentUri)
            if (selectedPlaylist.value?.id == playlistId) {
                val current = selectedPlaylist.value
                if (current != null) {
                    selectedPlaylist.value = current.copy(artworkUri = persistentUri)
                }
            }
        }
    }

    fun getTotalDurationPlayedBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<Long> = repository.getTotalDurationPlayedBetween(startTime, endTime)
    fun getDailyListeningStats(days: Int = 7): Flow<List<com.example.data.model.DailyListeningStat>> = repository.getDailyListeningStats(days)
    fun getTimeOfDayDistribution(startTime: Long): Flow<com.example.data.model.TimeOfDayDistribution> = repository.getTimeOfDayDistribution(startTime)
    fun getTrackCountBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<Int> = repository.getTrackCountBetween(startTime, endTime)
    fun getUniqueTrackCountBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<Int> = repository.getUniqueTrackCountBetween(startTime, endTime)
    fun getMostPlayedSongsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 20): Flow<List<Song>> = repository.getMostPlayedSongsBetween(startTime, endTime, limit)
    fun getTopArtistsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 10): Flow<List<com.example.data.model.ArtistStat>> = repository.getTopArtistsBetween(startTime, endTime, limit)
    fun getTopAlbumsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 10): Flow<List<com.example.data.model.AlbumStat>> = repository.getTopAlbumsBetween(startTime, endTime, limit)
    fun getMostPlayedPlaylistsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 10): Flow<List<Playlist>> = repository.getMostPlayedPlaylistsBetween(startTime, endTime, limit)

    fun toggleFavorite(song: Song) {
        val newFav = !song.isFavorite
        playbackManager.updateSongFavoriteStatus(song.id, newFav)
        viewModelScope.launch(Dispatchers.IO) {
            repository.setFavorite(song.id, newFav, song.path)
        }
    }

    fun clearAllFavorites() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAllFavorites()
        }
    }

    fun recordPlaylistPlay(playlistId: Long) {
        viewModelScope.launch {
            repository.recordPlaylistPlay(playlistId)
        }
    }

    fun selectPlaylist(playlist: Playlist?) {
        selectedPlaylist.value = playlist
    }

    fun selectSmartPlaylist(smartPlaylist: com.example.data.model.SmartPlaylist?) {
        selectedSmartPlaylist.value = smartPlaylist
    }

    fun createSmartPlaylist(smartPlaylist: com.example.data.model.SmartPlaylist) {
        viewModelScope.launch {
            repository.createSmartPlaylist(smartPlaylist)
        }
    }

    fun deleteSmartPlaylist(id: Long) {
        viewModelScope.launch {
            repository.deleteSmartPlaylist(id)
            if (selectedSmartPlaylist.value?.id == id) {
                selectedSmartPlaylist.value = null
            }
        }
    }


    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repository.createPlaylist(name)
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
            if (selectedPlaylist.value?.id == playlistId) {
                selectedPlaylist.value = selectedPlaylist.value?.copy(name = newName)
            }
        }
    }

    fun createPlaylistWithSongs(name: String, songs: List<Song>, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val playlistId = repository.createPlaylist(name)
            songs.forEach { song ->
                repository.addSongToPlaylist(playlistId, song.id)
            }
            onCreated(playlistId)
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val addAtTop = preferences.playlistAddSongPosition.first() == "TOP"
            val added = repository.addSongToPlaylist(playlistId, songId, addAtTop)
            onResult(added)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    fun removeDuplicateSongsFromPlaylist(playlistId: Long, onResult: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val count = repository.removeDuplicateSongsFromPlaylist(playlistId)
            onResult(count)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if (selectedPlaylist.value?.id == playlistId) {
                selectedPlaylist.value = null
            }
        }
    }


    fun playPlaylist(playlistId: Long, forceShuffle: Boolean = false) {
        viewModelScope.launch {
            recordPlaylistPlay(playlistId)
            val songs = repository.getPlaylistSongs(playlistId).first()
            if (songs.isNotEmpty()) {
                playbackManager.playQueue(songs, startIndex = 0, sourceContext = "PLAYLIST", forceShuffle = forceShuffle)
            }
        }
    }

    fun playPlaylistNext(playlistId: Long, onAdded: ((Int) -> Unit)? = null) {
        viewModelScope.launch {
            val songs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.getPlaylistSongs(playlistId).first()
            }
            if (songs.isNotEmpty()) {
                playbackManager.playNext(songs)
                onAdded?.invoke(songs.size)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                recordPlaylistPlay(playlistId)
            }
        }
    }

    fun addPlaylistToQueue(playlistId: Long, onAdded: ((Int) -> Unit)? = null) {
        viewModelScope.launch {
            val songs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.getPlaylistSongs(playlistId).first()
            }
            if (songs.isNotEmpty()) {
                playbackManager.addToQueue(songs)
                onAdded?.invoke(songs.size)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                recordPlaylistPlay(playlistId)
            }
        }
    }

    fun getPlaylistSongs(playlistId: Long): Flow<List<Song>> {
        return repository.getPlaylistSongs(playlistId)
    }

    fun reorderPlaylistSongs(playlistId: Long, newSongs: List<Song>) = viewModelScope.launch(Dispatchers.IO) {
        if (newSongs.isEmpty()) return@launch
        repository.updatePlaylistSongOrder(playlistId, newSongs.map { it.id })
    }

    fun addFolder(path: String, name: String, isExcluded: Boolean) {
        viewModelScope.launch {
            repository.addFolder(path, name, isExcluded)
            scanLibrary()
        }
    }

    fun removeFolder(path: String) {
        viewModelScope.launch {
            repository.removeFolder(path)
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            preferences.setOnboardingCompleted(true)
        }
    }

    fun startSleepTimerMinutes(minutes: Int, finishLastSong: Boolean = false) = sleepTimerManager.startTimerMinutes(minutes, finishLastSong)
    fun startSleepTimerSongs(count: Int) = playbackManager.startTrackSleepTimer(count)
    fun addSleepTimerMinutes(minutes: Int) = sleepTimerManager.addMinutes(minutes)
    fun cancelSleepTimer() = sleepTimerManager.cancelTimer()

    fun associateCustomLrc(song: Song, lrcContent: String) {
        viewModelScope.launch {
            val (plain, synced) = com.example.util.LrcParser.parseLrcContent(lrcContent)
            val entity = com.example.data.local.LyricsEntity(
                songId = song.id,
                plainLyrics = plain,
                syncedLyricsJson = synced,
                isCached = true,
                source = "Custom File"
            )
            db.lyricsDao().insertLyrics(entity)
            if (playbackState.value.currentSong?.id == song.id) {
                _currentLyrics.value = entity.toDomainModel()
            }
        }
    }

    val lyricsSearchResults = MutableStateFlow<List<com.example.service.LyricsCandidate>>(emptyList())
    val isSearchingLyrics = MutableStateFlow(false)
    private var lastLyricsSearchSongId: Long? = null
    private var lastLyricsSearchQuery: String = ""
    private var cachedLyricsCandidates: List<com.example.service.LyricsCandidate> = emptyList()

    fun searchLyricsCandidates(query: String, forceRefresh: Boolean = false) {
        val targetSongId = playbackState.value.currentSong?.id
        val cleanQuery = query.trim()

        if (!forceRefresh && targetSongId != null && targetSongId == lastLyricsSearchSongId && cleanQuery.equals(lastLyricsSearchQuery, ignoreCase = true) && cachedLyricsCandidates.isNotEmpty()) {
            lyricsSearchResults.value = cachedLyricsCandidates
            return
        }

        viewModelScope.launch {
            isSearchingLyrics.value = true
            if (targetSongId != lastLyricsSearchSongId) {
                lyricsSearchResults.value = emptyList()
            }
            val results = lyricsFetcher.searchAvailableLyrics(cleanQuery)
            lyricsSearchResults.value = results
            lastLyricsSearchSongId = targetSongId
            lastLyricsSearchQuery = cleanQuery
            cachedLyricsCandidates = results
            isSearchingLyrics.value = false
        }
    }

    fun applySelectedLyrics(candidate: com.example.service.LyricsCandidate) {
        val song = playbackState.value.currentSong ?: return
        viewModelScope.launch {
            val updatedLyrics = lyricsFetcher.saveSelectedLyrics(
                songId = song.id,
                plainLyrics = candidate.plainLyrics,
                syncedLyricsRaw = candidate.syncedLyricsRaw,
                source = candidate.source
            )
            _currentLyrics.value = updatedLyrics
        }
    }

    fun forceFetchLyrics(song: Song) = viewModelScope.launch {
        val isCurrentPlaying = playbackState.value.currentSong?.id == song.id
        if (isCurrentPlaying) {
            _currentLyrics.value = com.example.data.model.Lyrics(songId = song.id, plainLyrics = null, syncedLyricsJson = null, isCached = false, source = "loading")
        }
        try {
            val fetched = lyricsFetcher.getOrFetchLyrics(song)
            if (isCurrentPlaying && playbackState.value.currentSong?.id == song.id) {
                _currentLyrics.value = fetched
            }
        } catch (e: Exception) {
            if (isCurrentPlaying && playbackState.value.currentSong?.id == song.id) {
                _currentLyrics.value = com.example.data.model.Lyrics(songId = -1)
            }
        }
    }

    suspend fun getLyricsTextForSong(song: Song): String {
        val l = lyricsFetcher.getOrFetchLyrics(song) ?: return ""
        if (!l.syncedLyricsJson.isNullOrBlank()) {
            val formatted = com.example.util.LrcParser.formatSyncedJsonToLrc(l.syncedLyricsJson)
            if (!formatted.isNullOrBlank()) return formatted
        }
        return l.plainLyrics ?: ""
    }

    fun updateSongTags(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newArtworkUri: String?,
        newLyrics: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val updatedSong = repository.updateSongTags(
                song = song,
                newTitle = newTitle,
                newArtist = newArtist,
                newAlbum = newAlbum,
                newArtworkUri = newArtworkUri,
                newLyrics = newLyrics,
                context = getApplication()
            )
            com.example.util.ArtworkManager.invalidateSongArtwork(getApplication(), updatedSong.id)
            if (!updatedSong.artworkUri.isNullOrBlank()) {
                com.example.ui.components.ArtworkResolutionCache.cache[updatedSong.id] = updatedSong.artworkUri
            }
            playbackManager.updateSongInQueue(updatedSong)
            if (playbackState.value.currentSong?.id == updatedSong.id) {
                _currentLyrics.value = lyricsFetcher.getOrFetchLyrics(updatedSong)
                val artUri = updatedSong.artworkUri
                if (!artUri.isNullOrBlank()) {
                    val extracted = com.example.util.ArtworkColorExtractor.extractPrimaryColor(getApplication(), artUri)
                    _dynamicArtworkColor.value = extracted
                } else {
                    _dynamicArtworkColor.value = null
                }
            }
            refreshCacheSizes()
            onSuccess()
        }
    }

    fun getLyricsSyncOffset(songId: Long): Long {
        return preferences.getLyricsSyncOffsetMs(songId)
    }

    fun setLyricsSyncOffset(songId: Long, offsetMs: Long) {
        preferences.setLyricsSyncOffsetMs(songId, offsetMs)
    }

    suspend fun getWrappedDataForPeriod(startTime: Long, endTime: Long = 9223372036854775807L): WrappedData {
        return repository.getWrappedDataForPeriod(startTime, endTime)
    }

    override fun onCleared() {
        audioRouteManager.unregister()
        playbackManager.release()
        super.onCleared()
    }
}
