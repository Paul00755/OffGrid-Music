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
import com.example.auth.AuthManager
import com.example.auth.UserAccount
import com.example.auth.AuthUiState
import com.example.auth.PasswordResetUiState
import com.example.auth.GoogleSignInCancelledException
import android.app.Activity
import android.net.Uri
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.TranscribeLanguage
import com.example.service.transcription.GeminiLyricsService
import com.example.service.transcription.LyricsTranslationCache
import com.example.service.transcription.AudioRecordHelper
import com.example.service.transcription.SecureApiKeyStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    val preferences: UserPreferencesRepository = UserPreferencesRepository.getInstance(application)
    private val db = AppDatabase.getInstance(application)
    private val scanner = LibraryScanner.getInstance(application, db.songDao(), db.folderDao())
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
    val authManager: AuthManager = AuthManager.getInstance(application)
    val cloudSyncRepository: com.example.data.sync.CloudSyncRepository = com.example.data.sync.CloudSyncRepository.getInstance(
        context = application,
        songDao = db.songDao(),
        playlistDao = db.playlistDao(),
        historyDao = db.historyDao(),
        smartPlaylistDao = db.smartPlaylistDao(),
        userPreferences = preferences,
        folderDao = db.folderDao(),
        recentSearchDao = db.recentSearchDao()
    )

    val syncStatus: StateFlow<com.example.data.sync.SyncStatus> = cloudSyncRepository.syncStatus
    val backupStatus: StateFlow<com.example.data.sync.BackupStatus> = cloudSyncRepository.backupStatus
    val lastSyncedTimestamp: StateFlow<Long> = cloudSyncRepository.lastSyncedTimestamp
    val lastBackupTimestamp: StateFlow<Long> = cloudSyncRepository.lastBackupTimestamp

    val automaticBackupEnabled: StateFlow<Boolean> = preferences.automaticBackupEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAutomaticBackupEnabledSync())

    val backupFrequency: StateFlow<com.example.data.sync.BackupFrequency> = preferences.backupFrequency
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getBackupFrequencySync())

    fun triggerCloudSync() {
        cloudSyncRepository.performSyncNow()
    }

    fun triggerBackupNow() {
        cloudSyncRepository.triggerBackupNow()
    }

    fun setAutomaticBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setAutomaticBackupEnabled(enabled)
                val freq = preferences.getBackupFrequencySync()
                com.example.data.sync.AutoBackupWorker.scheduleOrUpdate(getApplication(), freq, enabled)
            }
        }
    }

    fun setBackupFrequency(frequency: com.example.data.sync.BackupFrequency) {
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setBackupFrequency(frequency)
                val isEnabled = preferences.getAutomaticBackupEnabledSync()
                com.example.data.sync.AutoBackupWorker.scheduleOrUpdate(getApplication(), frequency, isEnabled)
            }
        }
    }

    val userAccount: StateFlow<UserAccount?> = authManager.userAccountFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val skipAuthPrompt: StateFlow<Boolean> = preferences.skipAuthPrompt
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val customDisplayName: StateFlow<String?> = preferences.customDisplayName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val customAvatarUri: StateFlow<String?> = preferences.customAvatarUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // User-facing display name with fallback to Firebase displayName or email prefix or Guest.
    val effectiveDisplayName: StateFlow<String> = combine(userAccount, customDisplayName) { account, customName ->
        if (account == null) {
            "Music Lover"
        } else {
            customName?.takeIf { it.isNotBlank() }
                ?: account.displayName?.takeIf { it.isNotBlank() }
                ?: account.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
                ?: "Music Lover"
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "Music Lover")

    val authUiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val showAuthScreen = MutableStateFlow(false)
    val showAccountScreen = MutableStateFlow(false)

    private var passwordResetJob: kotlinx.coroutines.Job? = null
    val passwordResetState = MutableStateFlow<PasswordResetUiState>(PasswordResetUiState.Idle)

    fun resetAuthUiState() {
        authUiState.value = AuthUiState.Idle
    }

    fun resetPasswordResetState() {
        passwordResetJob?.cancel()
        passwordResetJob = null
        passwordResetState.value = PasswordResetUiState.Idle
    }

    fun openAuthScreen() {
        authUiState.value = AuthUiState.Idle
        showAuthScreen.value = true
    }

    fun closeAuthScreen() {
        showAuthScreen.value = false
        authUiState.value = AuthUiState.Idle
    }

    fun openAccountScreen() {
        showAccountScreen.value = true
    }

    fun closeAccountScreen() {
        showAccountScreen.value = false
    }

    fun continueWithoutSignUp() {
        viewModelScope.launch {
            preferences.setSkipAuthPrompt(true)
            showAuthScreen.value = false
            authUiState.value = AuthUiState.Idle
        }
    }

    fun signInWithGoogle(activity: Activity) {
        // Keep the existing auth surface visible so cancellation and sign-in errors are shown
        // when Google sign-in is started from the signed-out Cloud & Sync overlay.
        showAuthScreen.value = true
        authUiState.value = AuthUiState.Idle
        viewModelScope.launch {
            authUiState.value = AuthUiState.Loading
            val result = authManager.signInWithGoogle(activity)
            result.fold(
                onSuccess = { account ->
                    if (!account.displayName.isNullOrBlank() && customDisplayName.value.isNullOrBlank()) {
                        preferences.setCustomDisplayName(account.displayName)
                    }
                    if (!account.photoUrl.isNullOrBlank() && customAvatarUri.value.isNullOrBlank()) {
                        preferences.setCustomAvatarUri(account.photoUrl)
                    }
                    preferences.setSkipAuthPrompt(true)
                    authUiState.value = AuthUiState.Success("Signed in successfully with Google")
                    showAuthScreen.value = false
                    cloudSyncRepository.onSuccessfulSignIn(account.uid)
                },
                onFailure = { error ->
                    if (error is GoogleSignInCancelledException ||
                        error.message?.contains("cancelled", ignoreCase = true) == true ||
                        error.message?.contains("canceled", ignoreCase = true) == true) {
                        authUiState.value = AuthUiState.Idle
                    } else {
                        authUiState.value = AuthUiState.Error(error.message ?: "Google sign-in failed")
                    }
                }
            )
        }
    }

    fun signInWithEmail(email: String, pass: String) {
        if (email.isBlank() || pass.isBlank()) {
            authUiState.value = AuthUiState.Error("Please enter both email and password")
            return
        }
        viewModelScope.launch {
            authUiState.value = AuthUiState.Loading
            val result = authManager.signInWithEmail(email, pass)
            result.fold(
                onSuccess = { account ->
                    if (!account.displayName.isNullOrBlank() && customDisplayName.value.isNullOrBlank()) {
                        preferences.setCustomDisplayName(account.displayName)
                    }
                    if (!account.photoUrl.isNullOrBlank() && customAvatarUri.value.isNullOrBlank()) {
                        preferences.setCustomAvatarUri(account.photoUrl)
                    }
                    preferences.setSkipAuthPrompt(true)
                    authUiState.value = AuthUiState.Success("Signed in successfully")
                    showAuthScreen.value = false
                    cloudSyncRepository.onSuccessfulSignIn(account.uid)
                },
                onFailure = { error ->
                    authUiState.value = AuthUiState.Error(error.message ?: "Sign in failed")
                }
            )
        }
    }

    fun signUpWithEmail(email: String, pass: String, displayName: String?) {
        if (email.isBlank() || pass.isBlank()) {
            authUiState.value = AuthUiState.Error("Please enter both email and password")
            return
        }
        if (pass.length < 6) {
            authUiState.value = AuthUiState.Error("Password must be at least 6 characters")
            return
        }
        viewModelScope.launch {
            authUiState.value = AuthUiState.Loading
            val result = authManager.signUpWithEmail(email, pass, displayName)
            result.fold(
                onSuccess = { account ->
                    if (!displayName.isNullOrBlank()) {
                        preferences.setCustomDisplayName(displayName.trim())
                    }
                    preferences.setSkipAuthPrompt(true)
                    authUiState.value = AuthUiState.Success("Account created successfully")
                    showAuthScreen.value = false
                    cloudSyncRepository.onSuccessfulSignIn(account.uid)
                },
                onFailure = { error ->
                    authUiState.value = AuthUiState.Error(error.message ?: "Sign up failed")
                }
            )
        }
    }

    fun sendPasswordReset(email: String, onDone: ((Boolean, String) -> Unit)? = null) {
        val currentState = passwordResetState.value
        if (currentState is PasswordResetUiState.Sending || currentState is PasswordResetUiState.SentCooldown) {
            onDone?.invoke(false, "Please wait for cooldown to finish before requesting another email.")
            return
        }

        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            val errorMsg = "Please enter a valid email address."
            passwordResetState.value = PasswordResetUiState.Error(errorMsg)
            onDone?.invoke(false, errorMsg)
            return
        }

        passwordResetJob?.cancel()
        passwordResetJob = viewModelScope.launch {
            passwordResetState.value = PasswordResetUiState.Sending
            val result = authManager.sendPasswordReset(trimmedEmail)
            result.fold(
                onSuccess = {
                    val successMsg = "Password reset email sent.\nCheck your inbox and spam folder.\nYou can request another email in 60 seconds."
                    onDone?.invoke(true, successMsg)
                    for (remaining in 60 downTo 1) {
                        passwordResetState.value = PasswordResetUiState.SentCooldown(
                            secondsRemaining = remaining,
                            message = successMsg
                        )
                        kotlinx.coroutines.delay(1000L)
                    }
                    passwordResetState.value = PasswordResetUiState.ReadyToResend(
                        message = "Password reset email sent.\nDidn't receive it? You can now request another email."
                    )
                },
                onFailure = { error ->
                    val errorMsg = error.message ?: "Failed to send reset email. Please try again."
                    passwordResetState.value = PasswordResetUiState.Error(errorMsg)
                    onDone?.invoke(false, errorMsg)
                }
            )
        }
    }

    fun updateUserDisplayName(newName: String) {
        viewModelScope.launch {
            preferences.setCustomDisplayName(newName.trim())
            if (userAccount.value != null) {
                authManager.updateDisplayName(newName.trim())
            }
        }
    }

    fun updateProfileAvatar(uri: String?) {
        viewModelScope.launch {
            preferences.setCustomAvatarUri(uri)
            if (userAccount.value != null && uri != null) {
                try {
                    authManager.updatePhotoUrl(Uri.parse(uri))
                } catch (_: Exception) {}
            }
        }
    }

    fun signOut(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            cloudSyncRepository.onAuthStateChanged(null)
            authManager.signOut()
            preferences.resetAccountSpecificData()
            showAccountScreen.value = false
            onComplete()
        }
    }

    val playbackManager: PlaybackManager = PlaybackManager.getInstance(application)
    val sleepTimerManager: SleepTimerManager = playbackManager.sleepTimerManager
    val lyricsFetcher: LyricsFetcher = LyricsFetcher(db.lyricsDao(), application)
    val audioRouteManager: com.example.util.AudioRouteManager = com.example.util.AudioRouteManager(application)
    val currentAudioRoute: StateFlow<com.example.util.AudioRouteInfo> = audioRouteManager.currentRoute
    val availableAudioDevices: StateFlow<List<com.example.util.AudioRouteDevice>> = audioRouteManager.availableDevices

    fun selectAudioRoute(device: com.example.util.AudioRouteDevice) {
        audioRouteManager.selectRoute(device)
        val resolvedDevice = audioRouteManager.resolveDeviceInfo(device)
        playbackManager.setPreferredAudioDevice(resolvedDevice)
    }
    fun launchSystemOutputSwitcher(context: android.content.Context) = audioRouteManager.launchSystemOutputSwitcher(context)

    // UI Navigation State
    val selectedTab = MutableStateFlow(NavTab.HOME)
    private val tabHistory = java.util.ArrayDeque<NavTab>()

    fun navigateToTab(tab: NavTab) {
        val current = selectedTab.value
        if (current != tab) {
            tabHistory.remove(tab)
            tabHistory.addLast(current)
            selectedTab.value = tab
        }
    }

    fun navigateBackTab(): Boolean {
        while (tabHistory.isNotEmpty()) {
            val prev = tabHistory.removeLast()
            if (prev != selectedTab.value) {
                selectedTab.value = prev
                return true
            }
        }
        if (selectedTab.value != NavTab.HOME) {
            selectedTab.value = NavTab.HOME
            return true
        }
        return false
    }

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
        navigateToTab(NavTab.SETTINGS)
    }

    fun dismissSplash() {
        showSplash.value = false
    }

    // Data Flows
    val ignoreAudioDuration: StateFlow<Int> = preferences.ignoreAudioDuration
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getIgnoreAudioDurationSync())

    val maxSongDuration: StateFlow<Int> = preferences.maxSongDuration
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getMaxSongDurationSync())

    val fullDeviceScan: StateFlow<Boolean> = preferences.fullDeviceScan
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getFullDeviceScanSync())

    val allSongs: StateFlow<List<Song>> = kotlinx.coroutines.flow.combine(repository.allSongs, ignoreAudioDuration, maxSongDuration) { list, minSec, maxSec ->
        list.filter {
            val durSec = (it.durationMs / 1000L).toInt()
            val minPass = minSec <= 0 || durSec >= minSec
            val maxPass = maxSec >= 300 || durSec <= maxSec
            minPass && maxPass
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val favoriteSongs: StateFlow<List<Song>> = kotlinx.coroutines.flow.combine(repository.favoriteSongs, ignoreAudioDuration, maxSongDuration) { list, minSec, maxSec ->
        list.filter {
            val durSec = (it.durationMs / 1000L).toInt()
            val minPass = minSec <= 0 || durSec >= minSec
            val maxPass = maxSec >= 300 || durSec <= maxSec
            minPass && maxPass
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentlyAddedSongs: StateFlow<List<Song>> = kotlinx.coroutines.flow.combine(repository.recentlyAddedSongs, ignoreAudioDuration, maxSongDuration) { list, minSec, maxSec ->
        list.filter {
            val durSec = (it.durationMs / 1000L).toInt()
            val minPass = minSec <= 0 || durSec >= minSec
            val maxPass = maxSec >= 300 || durSec <= maxSec
            minPass && maxPass
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentlyPlayedSongs: StateFlow<List<Song>> = kotlinx.coroutines.flow.combine(repository.recentlyPlayedSongs, ignoreAudioDuration, maxSongDuration) { list, minSec, maxSec ->
        list.filter {
            val durSec = (it.durationMs / 1000L).toInt()
            val minPass = minSec <= 0 || durSec >= minSec
            val maxPass = maxSec >= 300 || durSec <= maxSec
            minPass && maxPass
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedSongs: StateFlow<List<Song>> = kotlinx.coroutines.flow.combine(repository.mostPlayedSongs, ignoreAudioDuration, maxSongDuration) { list, minSec, maxSec ->
        list.filter {
            val durSec = (it.durationMs / 1000L).toInt()
            val minPass = minSec <= 0 || durSec >= minSec
            val maxPass = maxSec >= 300 || durSec <= maxSec
            minPass && maxPass
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allFolders: StateFlow<List<FolderInfo>> = repository.allFolders.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allPlaylists: StateFlow<List<Playlist>> = repository.allPlaylists.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val mostPlayedPlaylists: StateFlow<List<Playlist>> = repository.mostPlayedPlaylists.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val smartPlaylists: StateFlow<List<com.example.data.model.SmartPlaylist>> = repository.smartPlaylists.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentSearches: StateFlow<List<String>> = repository.recentSearches.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val scanProgress: StateFlow<ScanProgress> = repository.scanProgress.stateIn(viewModelScope, SharingStarted.Lazily, ScanProgress())
    val playbackState: StateFlow<PlaybackState> = playbackManager.playbackState
    val playbackPositionMs: StateFlow<Long> = playbackManager.playbackPositionMs
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimerManager.state
    private val _isOnboardingCompleted = MutableStateFlow(preferences.isOnboardingCompletedSync())
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted.asStateFlow()
    val themeMode: StateFlow<String> = preferences.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getThemeModeSync())
    val uiTheme: StateFlow<String> = preferences.uiTheme.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getUiThemeSync())
    val homeScreenLayout: StateFlow<String> = preferences.homeScreenLayout.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getHomeScreenLayoutSync())
    val nowPlayingLayout: StateFlow<String> = preferences.nowPlayingLayout.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getNowPlayingLayoutSync())
    val playlistsLayout: StateFlow<String> = preferences.playlistsLayout.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getPlaylistsLayoutSync())
    val librarySortOption: StateFlow<String> = preferences.librarySortOption.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getLibrarySortOptionSync())
    val librarySortAscending: StateFlow<Boolean> = preferences.librarySortAscending.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getLibrarySortAscendingSync())
    val playlistViewMode: StateFlow<String> = preferences.playlistViewMode.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getPlaylistViewModeSync())
    val waveformStyle: StateFlow<String> = preferences.waveformStyle.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getWaveformStyleSync())
    val accentColorPreset: StateFlow<String> = preferences.accentColorPreset.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAccentColorPresetSync())
    val customAccentColor: StateFlow<Int> = preferences.customAccentColor.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getCustomAccentColorSync())
    val isDynamicArtworkTheme: StateFlow<Boolean> = preferences.isDynamicArtworkTheme.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getDynamicArtworkThemeSync())

    private val _dynamicArtworkColor = MutableStateFlow<androidx.compose.ui.graphics.Color?>(null)
    val dynamicArtworkColor: StateFlow<androidx.compose.ui.graphics.Color?> = _dynamicArtworkColor.asStateFlow()

    val cornerRadiusDp: StateFlow<Int> = preferences.cornerRadiusDp.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getCornerRadiusDpSync())
    val fontSizeScale: StateFlow<String> = preferences.fontSizeScale.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getFontSizeScaleSync())
    val artworkSize: StateFlow<String> = preferences.artworkSize.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getArtworkSizeSync())
    val showArtist: StateFlow<Boolean> = preferences.showArtist.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getShowArtistSync())
    val showAlbum: StateFlow<Boolean> = preferences.showAlbum.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getShowAlbumSync())
    val showAudioOutput: StateFlow<Boolean> = preferences.showAudioOutput.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getShowAudioOutputSync())
    val miniPlayerStyle: StateFlow<String> = preferences.miniPlayerStyle.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getMiniPlayerStyleSync())
    val miniPlayerShowPrevious: StateFlow<Boolean> = preferences.miniPlayerShowPrevious.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getMiniPlayerShowPreviousSync())
    val miniPlayerShowNext: StateFlow<Boolean> = preferences.miniPlayerShowNext.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getMiniPlayerShowNextSync())
    val miniPlayerShowFavorite: StateFlow<Boolean> = preferences.miniPlayerShowFavorite.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getMiniPlayerShowFavoriteSync())
    val gaplessPlayback: StateFlow<Boolean> = preferences.gaplessPlayback.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getGaplessPlaybackSync())
    val normalizeAudio: StateFlow<Boolean> = preferences.normalizeAudio.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getNormalizeAudioSync())
    val autoPlayOnHeadphoneConnection: StateFlow<Boolean> = preferences.autoPlayOnHeadphoneConnection.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAutoPlayOnHeadphoneConnectionSync())
    val autoPlayOnAppStartup: StateFlow<Boolean> = preferences.autoPlayOnAppStartup.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAutoPlayOnAppStartupSync())
    val addToQueuePosition: StateFlow<String> = preferences.addToQueuePosition.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAddToQueuePositionSync())
    val playlistAddSongPosition: StateFlow<String> = preferences.playlistAddSongPosition.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getPlaylistAddSongPositionSync())
    val crossfadeDurationSec: StateFlow<Int> = preferences.crossfadeDurationSec.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getCrossfadeDurationSecSync())
    val lyricsAutoScroll: StateFlow<Boolean> = preferences.lyricsAutoScroll.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getLyricsAutoScrollSync())
    val lyricsFontSize: StateFlow<String> = preferences.lyricsFontSize.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getLyricsFontSizeSync())
    val notificationEnabled: StateFlow<Boolean> = preferences.notificationEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getNotificationEnabledSync())
    val pauseOnZeroVolume: StateFlow<Boolean> = preferences.pauseOnZeroVolume.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getPauseOnZeroVolumeSync())
    val autoResumeOnVolumeIncrease: StateFlow<Boolean> = preferences.autoResumeOnVolumeIncrease.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAutoResumeOnVolumeIncreaseSync())
    val fadeInOutMs: StateFlow<Int> = preferences.fadeInOutMs.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getFadeInMsSync())
    val fadeInMs: StateFlow<Int> = preferences.fadeInMs.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getFadeInMsSync())
    val fadeOutMs: StateFlow<Int> = preferences.fadeOutMs.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getFadeOutMsSync())
    val shakeToSkip: StateFlow<Boolean> = preferences.shakeToSkip.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getShakeToSkipSync())
    val keepScreenAwake: StateFlow<Boolean> = preferences.keepScreenAwake.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getKeepScreenAwakeSync())
    val audioBalance: StateFlow<Float> = preferences.audioBalance.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAudioBalanceSync())
    val equalizerPreset: StateFlow<String> = preferences.equalizerPreset.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getEqualizerPresetSync())
    val reduceVolumeOnNotification: StateFlow<Boolean> = preferences.reduceVolumeOnNotification.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getReduceVolumeOnNotificationSync())
    val autoPauseCamera: StateFlow<Boolean> = preferences.autoPauseCamera.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAutoPauseCameraSync())
    val autoPauseSystemAudio: StateFlow<Boolean> = preferences.autoPauseSystemAudio.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAutoPauseSystemAudioSync())
    val playDuringCamera: StateFlow<Boolean> = preferences.playDuringCamera.stateIn(viewModelScope, SharingStarted.Eagerly, !preferences.getAutoPauseSystemAudioSync())
    val sleepTimerMode: StateFlow<String> = preferences.sleepTimerMode.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getSleepTimerModeSync())
    val sleepTimerFinishLastSong: StateFlow<Boolean> = preferences.sleepTimerFinishLastSong.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getSleepTimerFinishLastSongSync())
    val scrobbleThresholdType: StateFlow<String> = preferences.scrobbleThresholdType.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getScrobbleThresholdTypeSync())
    val scrobbleThresholdValue: StateFlow<Int> = preferences.scrobbleThresholdValue.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getScrobbleThresholdValueSync())
    val highRefreshRate: StateFlow<Boolean> = preferences.highRefreshRate.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getHighRefreshRateSync())
    val alwaysShuffleMode: StateFlow<Boolean> = preferences.alwaysShuffleMode.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAlwaysShuffleModeSync())
    val gesturePlaybackEnabled: StateFlow<Boolean> = preferences.gesturePlaybackEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getGesturePlaybackEnabledSync())
    val fetchOnlineArtworkHighRes: StateFlow<Boolean> = preferences.fetchOnlineArtworkHighRes.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getFetchOnlineArtworkHighResSync())
    val cacheLyricsEnabled: StateFlow<Boolean> = preferences.cacheLyricsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getCacheLyricsEnabledSync())
    val cacheAlbumArtEnabled: StateFlow<Boolean> = preferences.cacheAlbumArtEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getCacheAlbumArtEnabledSync())
    val cacheSongArtWorkEnabled: StateFlow<Boolean> = preferences.cacheSongArtWorkEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getCacheSongArtWorkEnabledSync())
    val customEqBands: StateFlow<List<Float>> = preferences.customEqBands.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getCustomEqBandsSync())
    val customBassBoost: StateFlow<Int> = preferences.customBassBoost.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getCustomBassBoostSync())
    val customVirtualizer: StateFlow<Int> = preferences.customVirtualizer.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getCustomVirtualizerSync())
    val homeSections: StateFlow<List<String>> = preferences.homeSections.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        preferences.getHomeSectionsSync()
    )
    val displayScale: StateFlow<String> = preferences.displayScale.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getDisplayScaleSync())

    private val _speedDialSongs = MutableStateFlow<List<Song>>(emptyList())
    val speedDialSongs: StateFlow<List<Song>> = _speedDialSongs.asStateFlow()

    fun refreshSpeedDial() {
        val songs = allSongs.value
        if (songs.isNotEmpty()) {
            _speedDialSongs.value = songs.shuffled().take(9)
        }
    }

    fun setHomeSections(sections: List<String>) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setHomeSections(sections)
        }
    }

    private val _lyricsCacheSize = MutableStateFlow("0.0 KB")
    val lyricsCacheSize: StateFlow<String> = _lyricsCacheSize.asStateFlow()

    private val _albumArtCacheSize = MutableStateFlow("0.0 KB")
    val albumArtCacheSize: StateFlow<String> = _albumArtCacheSize.asStateFlow()

    private val _songArtCacheSize = MutableStateFlow("0.0 KB")
    val songArtCacheSize: StateFlow<String> = _songArtCacheSize.asStateFlow()

    private val _totalCacheSize = MutableStateFlow("0.0 KB")
    val totalCacheSize: StateFlow<String> = _totalCacheSize.asStateFlow()

    val databaseStorageTier: StateFlow<String> = preferences.databaseStorageTier.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = preferences.getDatabaseStorageTierSync()
    )

    private val _databaseStorageStats = MutableStateFlow<com.example.data.local.DatabaseStorageStats?>(null)
    val databaseStorageStats: StateFlow<com.example.data.local.DatabaseStorageStats?> = _databaseStorageStats.asStateFlow()

    private val _isOptimizingDatabase = MutableStateFlow(false)
    val isOptimizingDatabase: StateFlow<Boolean> = _isOptimizingDatabase.asStateFlow()

    // Lyrics State
    private val _currentLyrics = MutableStateFlow<Lyrics?>(null)
    val currentLyrics: StateFlow<Lyrics?> = _currentLyrics.asStateFlow()

    val lyricsSearchResults = MutableStateFlow<List<com.example.service.LyricsCandidate>>(emptyList())
    val isSearchingLyrics = MutableStateFlow(false)
    private var lastLyricsSearchSongId: Long? = null
    private var lastLyricsSearchQuery: String = ""
    private var cachedLyricsCandidates: List<com.example.service.LyricsCandidate> = emptyList()

    // Audio & Lyrics Transcription / Translation State
    val secureApiKeyStore = SecureApiKeyStore(getApplication())
    val isTranscribeTranslateEnabled = preferences.isTranscribeTranslateEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getTranscribeTranslateEnabledSync())
    val audioTranscribeMode = preferences.audioTranscribeMode.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAudioTranscribeModeSync())
    val audioTranscribeTargetLang = preferences.audioTranscribeTargetLang.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.getAudioTranscribeTargetLangSync())

    private val _hasUserApiKey = MutableStateFlow(false)
    val hasUserApiKey: StateFlow<Boolean> = _hasUserApiKey.asStateFlow()

    private val _maskedApiKey = MutableStateFlow<String?>(null)
    val maskedApiKey: StateFlow<String?> = _maskedApiKey.asStateFlow()

    private val _isKeyValidating = MutableStateFlow(false)
    val isKeyValidating: StateFlow<Boolean> = _isKeyValidating.asStateFlow()

    private val _keyValidationErrorMessage = MutableStateFlow<String?>(null)
    val keyValidationErrorMessage: StateFlow<String?> = _keyValidationErrorMessage.asStateFlow()

    private val _isTranscribingLyrics = MutableStateFlow(false)
    val isTranscribingLyrics: StateFlow<Boolean> = _isTranscribingLyrics.asStateFlow()

    private val _transcribeStatusMessage = MutableStateFlow<String?>(null)
    val transcribeStatusMessage: StateFlow<String?> = _transcribeStatusMessage.asStateFlow()

    private val _transcribeErrorMessage = MutableStateFlow<String?>(null)
    val transcribeErrorMessage: StateFlow<String?> = _transcribeErrorMessage.asStateFlow()

    private val _transcribeLyricsPreview = MutableStateFlow<com.example.service.transcription.TranscribeLyricsPreview?>(null)
    val transcribeLyricsPreview: StateFlow<com.example.service.transcription.TranscribeLyricsPreview?> = _transcribeLyricsPreview.asStateFlow()

    private val previousLyricsHistory = ConcurrentHashMap<Long, Lyrics?>()
    private val _canUndoLyricsForCurrentSong = MutableStateFlow(false)
    val canUndoLyricsForCurrentSong: StateFlow<Boolean> = _canUndoLyricsForCurrentSong.asStateFlow()

    val geminiLyricsService = GeminiLyricsService(application)
    val audioRecordHelper = AudioRecordHelper(application)

    private var ignoreDurationJob: kotlinx.coroutines.Job? = null
    private var scanJob: kotlinx.coroutines.Job? = null
    private var quickScanJob: kotlinx.coroutines.Job? = null

    val equalizerManager get() = playbackManager.equalizerManager

    init {
        scanner.onScanCompletedListener = {
            cloudSyncRepository.onLibraryScanCompleted()
        }
        viewModelScope.launch {
            authManager.userAccountFlow.collect { account ->
                cloudSyncRepository.onAuthStateChanged(account?.uid)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            cloudSyncRepository.syncStatus.collect { status ->
                if (status is com.example.data.sync.SyncStatus.Synced) {
                    val activeSong = playbackState.value.currentSong ?: return@collect
                    val latestDbLyrics = db.lyricsDao().getLyricsForSong(activeSong.id)?.toDomainModel()
                    if (latestDbLyrics != null && latestDbLyrics != _currentLyrics.value) {
                        _currentLyrics.value = latestDbLyrics
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            preferences.isOnboardingCompleted.collect { completed ->
                _isOnboardingCompleted.value = completed
                if (completed) {
                    scanner.startAutoDetection()
                }
            }
        }
        audioRouteManager.onDeviceSelected = { dev ->
            playbackManager.setPreferredAudioDevice(dev)
        }
        viewModelScope.launch(Dispatchers.Default) {
            var lastWarmedLibrarySignature = 0L
            allSongs.collectLatest { songs ->
                if (songs.isNotEmpty()) {
                    val selectedSpeedDial = _speedDialSongs.value
                    val selectedIds = selectedSpeedDial.mapTo(HashSet()) { it.id }
                    val refreshedById = HashMap<Long, Song>(selectedIds.size)
                    val sampledSongs = if (selectedSpeedDial.isEmpty()) ArrayList<Song>(9) else null
                    var signature = songs.size.toLong()

                    songs.forEachIndexed { index, song ->
                        signature = signature * 31L + song.id * 17L + (song.artworkUri?.hashCode()?.toLong() ?: 0L)

                        if (sampledSongs != null) {
                            if (index < 9) {
                                sampledSongs.add(song)
                            } else {
                                val replacementIndex = kotlin.random.Random.Default.nextInt(index + 1)
                                if (replacementIndex < sampledSongs.size) sampledSongs[replacementIndex] = song
                            }
                        } else if (song.id in selectedIds) {
                            refreshedById[song.id] = song
                        }
                    }
                    val updatedSpeedDial = if (sampledSongs != null) {
                        sampledSongs
                    } else {
                        selectedSpeedDial.mapNotNull { refreshedById[it.id] }
                    }
                    if (updatedSpeedDial != selectedSpeedDial) {
                        _speedDialSongs.value = updatedSpeedDial
                    }
                    if (signature != lastWarmedLibrarySignature) {
                        // Scans emit a full-list snapshot for each database batch. Wait for a quiet
                        // interval before warming cached covers so intermediate snapshots do not
                        // repeatedly cancel and restart the same disk work.
                        kotlinx.coroutines.delay(350L)
                        lastWarmedLibrarySignature = signature
                        com.example.ui.components.ArtworkResolutionCache.warmAllSongsArtworkInBackground(getApplication(), songs)
                    }
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
            kotlinx.coroutines.flow.combine(
                playbackState.map { it.currentSong }.distinctUntilChanged(),
                isDynamicArtworkTheme
            ) { song, isDynamic ->
                Pair(song, isDynamic)
            }.collect { (song, isDynamic) ->
                if (isDynamic && song != null) {
                    val extracted = com.example.util.ArtworkColorExtractor.extractPrimaryColorForSong(getApplication(), song)
                    _dynamicArtworkColor.value = extracted
                } else {
                    _dynamicArtworkColor.value = null
                }
                com.example.widget.WidgetUpdateManager.updateAllWidgets(getApplication(), playbackState.value)
            }
        }
        audioRouteManager.onDeviceSelected = { resolvedDev ->
            playbackManager.setPreferredAudioDevice(resolvedDev)
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
            autoPauseCamera.collect { enabled ->
                playbackManager.autoPauseCamera = enabled
            }
        }
        viewModelScope.launch {
            autoPauseSystemAudio.collect { enabled ->
                playbackManager.autoPauseSystemAudio = enabled
            }
        }
        viewModelScope.launch {
            sleepTimerFinishLastSong.collect { enabled ->
                sleepTimerManager.setFinishLastSong(enabled)
            }
        }
        viewModelScope.launch {
            audioBalance.collect { balance ->
                playbackManager.setAudioBalance(balance)
            }
        }
        viewModelScope.launch {
            equalizerPreset.collect { preset ->
                if (preset.equals("CUSTOM", ignoreCase = true)) {
                    val bands = preferences.getCustomEqBandsSync()
                    val bass = preferences.getCustomBassBoostSync()
                    val virt = preferences.getCustomVirtualizerSync()
                    playbackManager.equalizerManager.setCustomBands(bands, bass, virt)
                } else {
                    playbackManager.equalizerManager.applyPreset(preset)
                }
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
            val currentUiTheme = preferences.uiTheme.firstOrNull() ?: preferences.getUiThemeSync()
            val currentTheme = com.example.ui.theme.AppUiTheme.fromId(currentUiTheme)
            val currentMode = (preferences.themeMode.firstOrNull() ?: preferences.getThemeModeSync()).uppercase()
            if (!com.example.ui.theme.AppUiTheme.isThemeCompatibleWith(currentTheme, currentMode)) {
                val fallback = com.example.ui.theme.AppUiTheme.getDefaultThemeForMode(currentMode)
                preferences.setUiTheme(fallback.id)
            }
        }
        viewModelScope.launch {
            combine(preferences.automaticBackupEnabled, preferences.backupFrequency) { enabled, freq ->
                enabled to freq
            }.collect { (enabled, freq) ->
                com.example.data.sync.AutoBackupWorker.scheduleOrUpdate(getApplication(), freq, enabled)
            }
        }
    }

    fun setThemeMode(mode: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            val targetMode = mode.uppercase()
            val currentTheme = com.example.ui.theme.AppUiTheme.fromId(uiTheme.value)
            val fallbackId = if (!com.example.ui.theme.AppUiTheme.isThemeCompatibleWith(currentTheme, targetMode))
                com.example.ui.theme.AppUiTheme.getDefaultThemeForMode(targetMode).id else null
            preferences.setThemeMode(targetMode, fallbackId)
            com.example.widget.WidgetUpdateManager.forceRefreshTheme(getApplication(), playbackState.value)
        }
    }

    fun setUiTheme(themeId: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setUiTheme(themeId)
            val selectedTheme = com.example.ui.theme.AppUiTheme.fromId(themeId)
            val currentMode = themeMode.value.uppercase()
            if (!com.example.ui.theme.AppUiTheme.isThemeCompatibleWith(selectedTheme, currentMode)) {
                val newMode = if (selectedTheme.isDark) "DARK" else "LIGHT"
                preferences.setThemeMode(newMode)
            }
            com.example.widget.WidgetUpdateManager.forceRefreshTheme(getApplication(), playbackState.value)
        }
    }

    fun setHomeScreenLayout(layoutId: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setHomeScreenLayout(layoutId) }
    }
    fun setNowPlayingLayout(layoutId: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setNowPlayingLayout(layoutId)
            val defaultWaveform = when (com.example.ui.theme.NowPlayingLayout.fromId(layoutId)) {
                com.example.ui.theme.NowPlayingLayout.MODERN_BENTO -> "SLEEK_LINE"
                com.example.ui.theme.NowPlayingLayout.NEON_WAVEFORM -> "BARS"
                com.example.ui.theme.NowPlayingLayout.VINYL_TURNTABLE -> "SNAKE"
                com.example.ui.theme.NowPlayingLayout.MINIMAL_GLASS -> "SNAKE"
            }
            preferences.setWaveformStyle(defaultWaveform)
        }
    }
    fun setPlaylistsLayout(layoutId: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setPlaylistsLayout(layoutId) }
    }
    fun setWaveformStyle(style: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setWaveformStyle(style) }
    }
    fun setAccentColorPreset(preset: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setAccentColorPreset(preset)
            com.example.widget.WidgetUpdateManager.forceRefreshTheme(getApplication(), playbackState.value)
        }
    }
    fun setCustomAccentColor(colorInt: Int) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setCustomAccentColor(colorInt)
            com.example.widget.WidgetUpdateManager.forceRefreshTheme(getApplication(), playbackState.value)
        }
    }
    fun setDynamicArtworkTheme(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setDynamicArtworkTheme(enabled)
            com.example.widget.WidgetUpdateManager.forceRefreshTheme(getApplication(), playbackState.value)
        }
    }
    fun setCornerRadiusDp(radius: Int) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setCornerRadiusDp(radius) }
    }
    fun setFontSizeScale(scale: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setFontSizeScale(scale) }
    }
    fun setArtworkSize(size: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setArtworkSize(size) }
    }
    fun setShowArtist(show: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setShowArtist(show) }
    }
    fun setShowAlbum(show: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setShowAlbum(show) }
    }
    fun setShowAudioOutput(show: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setShowAudioOutput(show) }
    }
    fun setMiniPlayerStyle(style: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setMiniPlayerStyle(style) }
    }
    fun setMiniPlayerShowPrevious(show: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setMiniPlayerShowPrevious(show) }
    }
    fun setMiniPlayerShowNext(show: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setMiniPlayerShowNext(show) }
    }
    fun setMiniPlayerShowFavorite(show: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setMiniPlayerShowFavorite(show) }
    }
    fun setAddToQueuePosition(position: String) {
        playbackManager.addToQueuePosition = position
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setAddToQueuePosition(position) }
        }
    }
    fun setPlaylistAddSongPosition(position: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setPlaylistAddSongPosition(position) }
    }
    fun setGaplessPlayback(enabled: Boolean) {
        playbackManager.gaplessPlayback = enabled
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setGaplessPlayback(enabled) }
        }
    }
    fun setCrossfadeDurationSec(sec: Int) {
        playbackManager.crossfadeDurationSec = sec
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setCrossfadeDurationSec(sec) }
        }
    }
    fun setLyricsAutoScroll(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setLyricsAutoScroll(enabled) }
    }
    fun setGesturePlaybackEnabled(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setGesturePlaybackEnabled(enabled) }
    }
    fun getLyricsOffsetMs(songId: Long): Long = preferences.getLyricsSyncOffsetMs(songId)
    fun setLyricsOffsetMs(songId: Long, offsetMs: Long) {
        preferences.setLyricsSyncOffsetMs(songId, offsetMs)
        cloudSyncRepository.onLyricsOffsetChangedAsync(songId, offsetMs)
        cloudSyncRepository.onLyricsUpdatedAsync(songId)
    }
    fun setLyricsFontSize(size: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setLyricsFontSize(size) }
    }
    fun setNotificationEnabled(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setNotificationEnabled(enabled) }
    }
    fun setAutoPlayOnHeadphoneConnection(enabled: Boolean) {
        playbackManager.autoPlayOnHeadphoneConnection = enabled
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setAutoPlayOnHeadphoneConnection(enabled) }
        }
    }
    fun setAutoPlayOnAppStartup(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setAutoPlayOnAppStartup(enabled) }
    }
    fun setPauseOnZeroVolume(enabled: Boolean) {
        playbackManager.pauseOnZeroVolume = enabled
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setPauseOnZeroVolume(enabled) }
        }
    }
    fun setAutoPauseCamera(enabled: Boolean) {
        playbackManager.autoPauseCamera = enabled
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setAutoPauseCamera(enabled) }
        }
    }
    fun setAutoPauseSystemAudio(enabled: Boolean) {
        playbackManager.autoPauseSystemAudio = enabled
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setAutoPauseSystemAudio(enabled) }
        }
    }
    fun setPlayDuringCamera(enabled: Boolean) {
        setAutoPauseSystemAudio(!enabled)
    }
    fun setAutoResumeOnVolumeIncrease(enabled: Boolean) {
        playbackManager.autoResumeOnVolumeIncrease = enabled
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setAutoResumeOnVolumeIncrease(enabled) }
        }
    }
    fun setIgnoreAudioDuration(sec: Int) {
        val safeSec = sec.coerceIn(0, 300)
        ignoreDurationJob?.cancel()
        ignoreDurationJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setIgnoreAudioDuration(safeSec)
            }
            if (preferences.isOnboardingCompleted.first()) {
                kotlinx.coroutines.delay(500)
                val isFull = preferences.fullDeviceScan.first()
                val maxMs = if (maxSongDuration.value >= 300) Long.MAX_VALUE else maxSongDuration.value * 1000L
                repository.quickScanAndCleanLibrary(safeSec * 1000L, maxMs, isFull)
            }
        }
    }

    fun setMaxSongDuration(sec: Int) {
        val safeSec = sec.coerceIn(0, 300)
        ignoreDurationJob?.cancel()
        ignoreDurationJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setMaxSongDuration(safeSec)
            }
            if (preferences.isOnboardingCompleted.first()) {
                kotlinx.coroutines.delay(500)
                val isFull = preferences.fullDeviceScan.first()
                val maxMs = if (safeSec >= 300) Long.MAX_VALUE else safeSec * 1000L
                repository.quickScanAndCleanLibrary(ignoreAudioDuration.value * 1000L, maxMs, isFull)
            }
        }
    }

    fun setFullDeviceScan(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setFullDeviceScan(enabled)
        }
    }

    fun setSongDurationRange(minSec: Int, maxSec: Int) {
        val safeMin = minSec.coerceIn(0, 300)
        val safeMax = maxSec.coerceIn(safeMin, 300)
        ignoreDurationJob?.cancel()
        ignoreDurationJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setSongDurationRange(safeMin, safeMax)
            }
            if (preferences.isOnboardingCompleted.first()) {
                kotlinx.coroutines.delay(500)
                val isFull = preferences.fullDeviceScan.first()
                val maxMs = if (safeMax >= 300) Long.MAX_VALUE else safeMax * 1000L
                repository.quickScanAndCleanLibrary(safeMin * 1000L, maxMs, isFull)
            }
        }
    }
    fun setFadeInOutMs(ms: Int) {
        playbackManager.fadeInOutMs = ms
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setFadeInOutMs(ms) }
        }
    }
    fun setDisplayScale(scale: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setDisplayScale(scale) }
    }
    fun setNormalizeAudio(enabled: Boolean) {
        playbackManager.isNormalizeAudioEnabled = enabled
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setNormalizeAudio(enabled) }
        }
    }
    fun setFadeInMs(ms: Int) {
        playbackManager.fadeInMs = ms
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setFadeInMs(ms) }
        }
    }
    fun setFadeOutMs(ms: Int) {
        playbackManager.fadeOutMs = ms
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setFadeOutMs(ms) }
        }
    }
    fun setShakeToSkip(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setShakeToSkip(enabled) }
    }
    fun setKeepScreenAwake(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setKeepScreenAwake(enabled) }
    }
    fun setAudioBalance(balance: Float) {
        playbackManager.setAudioBalance(balance)
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setAudioBalance(balance) }
        }
    }
    fun setEqualizerPreset(preset: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setEqualizerPreset(preset)
            val matchedPreset = com.example.ui.components.EQ_PRESETS.find { it.id.equals(preset, ignoreCase = true) }
            if (matchedPreset != null && !preset.equals("CUSTOM", ignoreCase = true)) {
                preferences.setCustomEqBands(matchedPreset.bands, markAsCustom = false)
                preferences.setCustomBassBoost(matchedPreset.bassBoost, markAsCustom = false)
                preferences.setCustomVirtualizer(matchedPreset.virtualizer, markAsCustom = false)
            }
            playbackManager.equalizerManager.applyPreset(preset)
        }
    }
    fun setReduceVolumeOnNotification(enabled: Boolean) {
        playbackManager.reduceVolumeOnNotification = enabled
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setReduceVolumeOnNotification(enabled) }
        }
    }
    fun setScrobbleThresholdType(type: String) {
        playbackManager.scrobbleThresholdType = type
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setScrobbleThresholdType(type) }
        }
    }
    fun setScrobbleThresholdValue(value: Int) {
        playbackManager.scrobbleThresholdValue = value
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setScrobbleThresholdValue(value) }
        }
    }
    fun setHighRefreshRate(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setHighRefreshRate(enabled) }
    }
    fun setAlwaysShuffleMode(enabled: Boolean) {
        playbackManager.alwaysShuffleMode = enabled
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) { preferences.setAlwaysShuffleMode(enabled) }
        }
    }
    fun setFetchOnlineArtworkHighRes(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setFetchOnlineArtworkHighRes(enabled) }
    }
    fun setCacheLyricsEnabled(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setCacheLyricsEnabled(enabled) }
    }
    fun setCacheAlbumArtEnabled(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setCacheAlbumArtEnabled(enabled) }
    }
    fun setCacheSongArtWorkEnabled(enabled: Boolean) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) { preferences.setCacheSongArtWorkEnabled(enabled) }
    }

    fun setCustomEqualizerBands(bands: List<Float>) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setCustomEqBands(bands)
            playbackManager.equalizerManager.setCustomBands(bands, customBassBoost.value, customVirtualizer.value)
        }
    }

    fun setCustomEqBands(bands: List<Float>) = setCustomEqualizerBands(bands)

    fun setCustomBassBoost(strength: Int) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setCustomBassBoost(strength)
            playbackManager.equalizerManager.setBassBoostStrength(strength)
        }
    }

    fun setCustomVirtualizer(strength: Int) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setCustomVirtualizer(strength)
            playbackManager.equalizerManager.setVirtualizerStrength(strength)
        }
    }


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


    fun setDatabaseStorageTier(tier: String) = viewModelScope.launch {
        withContext(kotlinx.coroutines.NonCancellable) {
            preferences.setDatabaseStorageTier(tier)
        }
        refreshDatabaseStats()
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

    fun updatePlaylistViewMode(mode: String) {
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setPlaylistViewMode(mode)
            }
        }
    }

    fun updateLibrarySort(option: String, ascending: Boolean) {
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.saveLibrarySortOption(option, ascending)
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hasKey = secureApiKeyStore.hasApiKey()
                val masked = if (hasKey) secureApiKeyStore.getMaskedApiKey() else null
                _hasUserApiKey.value = hasKey
                _maskedApiKey.value = masked
                if (preferences.isTranscribeTranslateEnabled.first() && !hasKey) {
                    preferences.setTranscribeTranslateEnabled(false)
                }
            } catch (_: Throwable) {}
        }

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

        // Restore playback state once songs are loaded (prepare queue on IO to avoid blocking Main Thread)
        viewModelScope.launch(Dispatchers.IO) {
            val loadedSongs = allSongs.filter { it.isNotEmpty() }.first()
            val savedRepeat = preferences.repeatMode.first()

            if (loadedSongs.isNotEmpty()) {
                val lastQueueIds = preferences.lastQueueIds.first()
                val lastSongId = preferences.lastSongId.first()
                val lastPos = preferences.lastPosition.first()
                val lastDuration = preferences.lastDuration.first()
                val lastIdx = preferences.lastQueueIndex.first()
                val shouldAutoPlay = preferences.autoPlayOnAppStartup.first()

                val songsMap = loadedSongs.associateBy { it.id }
                val restoredQueue = if (lastQueueIds.isNotEmpty()) {
                    lastQueueIds.mapNotNull { songsMap[it] }
                } else emptyList()

                val finalQueue = if (restoredQueue.isNotEmpty()) restoredQueue else loadedSongs
                val finalIndex = if (lastIdx in finalQueue.indices && lastSongId > 0 && finalQueue[lastIdx].id == lastSongId) {
                    lastIdx
                } else if (lastSongId > 0) {
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

                withContext(Dispatchers.Main) {
                    if (savedRepeat != 0) {
                        playbackManager.setRepeatMode(savedRepeat)
                    }
                    if (playbackManager.playbackState.value.queue.isEmpty() || !playbackManager.playbackState.value.isPlaying) {
                        playbackManager.restorePlaybackState(queueWithCorrectDuration, finalIndex, lastPos.coerceAtLeast(0L), reliableDuration)
                    }
                    val pendingAction = pendingWidgetAction
                    if (pendingAction != null) {
                        pendingWidgetAction = null
                        executeWidgetActionNow(pendingAction)
                    } else if (shouldAutoPlay && queueWithCorrectDuration.isNotEmpty() && !playbackManager.isVolumeZero()) {
                        playbackManager.play()
                    }
                }
            } else if (savedRepeat != 0) {
                withContext(Dispatchers.Main) {
                    playbackManager.setRepeatMode(savedRepeat)
                }
            }
        }

        // Automatically fetch lyrics when track changes and persist state
        val persistentSessionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        playbackManager.onUpdatePlaybackSession = { sessionId, trackId, startedAt, endedAt, listenedDurationMs, sourceContext, totalDurationMs ->
            persistentSessionScope.launch {
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
        var lastSavedIsPlaying: Boolean? = null

        viewModelScope.launch {
            playbackState.collect { state ->
                val song = state.currentSong
                if (song != null) {
                    if (song.id != lastFetchedSongId) {
                        lastFetchedSongId = song.id
                        lyricsFetchJob?.cancel()
                        lyricsSearchResults.value = emptyList()
                        lastLyricsSearchSongId = null
                        lastLyricsSearchQuery = ""
                        cachedLyricsCandidates = emptyList()
                        _canUndoLyricsForCurrentSong.value = previousLyricsHistory.containsKey(song.id)
                        _transcribeErrorMessage.value = null
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
                                        com.example.ui.components.ArtworkResolutionCache.notifyArtworkChanged(song.id, resolvedUri, song.path)
                                        playbackManager.updateCurrentSongArtwork(song.id, resolvedUri, song.path)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    // Throttle DataStore writes to prevent thread starvation and ANRs
                    val now = System.currentTimeMillis()
                    val isTrackChange = song.id != lastSavedSongId
                    val isPauseTransition = !state.isPlaying && lastSavedIsPlaying == true
                    val isThrottledTime = (now - lastSaveTime > 10000L) && kotlin.math.abs(state.positionMs - lastSavedPos) > 3000L
                    lastSavedIsPlaying = state.isPlaying

                    if (isTrackChange || isPauseTransition || isThrottledTime) {
                        lastSavedSongId = song.id
                        lastSavedPos = state.positionMs
                        lastSaveTime = now
                        val queueIdsSnapshot = state.queue.map { it.id }
                        val posSnapshot = state.positionMs
                        val idxSnapshot = state.queueIndex
                        viewModelScope.launch(Dispatchers.IO) {
                            preferences.saveLastPlaybackState(
                                songId = song.id,
                                position = posSnapshot,
                                queueIds = queueIdsSnapshot,
                                queueIndex = idxSnapshot
                            )
                        }
                    }
                } else {
                    lyricsFetchJob?.cancel()
                    _currentLyrics.value = null
                    lastFetchedSongId = null
                    lastSavedIsPlaying = null
                }
            }
        }
    }

    fun scanLibrary(forceFullScan: Boolean? = null) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val isFull = forceFullScan ?: preferences.fullDeviceScan.first()
            val minSec = preferences.ignoreAudioDuration.first()
            val maxSec = preferences.maxSongDuration.first()
            val maxMs = if (maxSec >= 300) Long.MAX_VALUE else maxSec * 1000L
            repository.scanLibrary(
                ignoreAudioDurationMs = minSec * 1000L,
                maxAudioDurationMs = maxMs,
                forceFullScan = isFull
            )
        }
    }

    @Volatile
    private var lastAutoQuickScanTimeMs = 0L

    fun quickScanAndCleanLibrary() {
        if (quickScanJob?.isActive == true || scanJob?.isActive == true) return
        lastAutoQuickScanTimeMs = android.os.SystemClock.elapsedRealtime()
        quickScanJob = viewModelScope.launch(Dispatchers.IO) {
            val isFull = preferences.fullDeviceScan.first()
            val minSec = preferences.ignoreAudioDuration.first()
            val maxSec = preferences.maxSongDuration.first()
            val maxMs = if (maxSec >= 300) Long.MAX_VALUE else maxSec * 1000L
            repository.quickScanAndCleanLibrary(
                ignoreAudioDurationMs = minSec * 1000L,
                maxAudioDurationMs = maxMs,
                forceFullScan = isFull,
                skipIfAlreadyScanning = false
            )
        }
    }

    /**
     * Triggered when the user enters the Home or Library screen.
     * Deduplicates rapid or simultaneous entries, reuses any active scan, respects storage permissions,
     * and runs a lightweight incremental quick scan in the background using the user's configured
     * Library & Folders settings (included/excluded folders, min/max duration, full-device scan mode).
     */
    fun onScreenEnteredForAutoScan(screenName: String, force: Boolean = false): Boolean {
        val isOnboarded = isOnboardingCompleted.value || preferences.isOnboardingCompletedSync()
        if (!isOnboarded) return false

        val appContext = getApplication<android.app.Application>()
        if (!force && !hasReadAudioPermission(appContext)) return false

        if (scanJob?.isActive == true || quickScanJob?.isActive == true || repository.isScanActive()) {
            return false
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastAutoQuickScanTimeMs != 0L && (now - lastAutoQuickScanTimeMs) < 2_500L) {
            return false
        }
        lastAutoQuickScanTimeMs = now

        quickScanJob = viewModelScope.launch(Dispatchers.IO) {
            val isFull = preferences.fullDeviceScan.first()
            val minSec = preferences.ignoreAudioDuration.first()
            val maxSec = preferences.maxSongDuration.first()
            val maxMs = if (maxSec >= 300) Long.MAX_VALUE else maxSec * 1000L
            repository.quickScanAndCleanLibrary(
                ignoreAudioDurationMs = minSec * 1000L,
                maxAudioDurationMs = maxMs,
                forceFullScan = isFull,
                skipIfAlreadyScanning = true
            )
        }
        return true
    }

    private fun hasReadAudioPermission(context: android.content.Context): Boolean {
        if (com.example.util.StoragePermissionHelper.hasStoragePermission(context)) return true
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
        val index = queue.indexOfFirst { it.id == song.id }.let { if (it >= 0) it else queue.indexOf(song).coerceAtLeast(0) }
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

    @Volatile
    private var pendingWidgetAction: String? = null

    fun enqueueOrExecuteWidgetAction(action: String) {
        if (playbackManager.playbackState.value.queue.isNotEmpty()) {
            pendingWidgetAction = null
            executeWidgetActionNow(action)
        } else {
            pendingWidgetAction = action
        }
    }

    private fun executeWidgetActionNow(action: String) {
        when (action) {
            com.example.widget.WidgetUpdateManager.ACTION_WIDGET_PLAY_PAUSE -> {
                playbackManager.togglePlayPause()
            }
            com.example.widget.WidgetUpdateManager.ACTION_WIDGET_NEXT -> {
                playbackManager.skipToNext()
                if (!playbackManager.playbackState.value.isPlaying) {
                    playbackManager.play()
                }
            }
            com.example.widget.WidgetUpdateManager.ACTION_WIDGET_PREV -> {
                playbackManager.skipToPrevious()
                if (!playbackManager.playbackState.value.isPlaying) {
                    playbackManager.play()
                }
            }
            com.example.widget.WidgetUpdateManager.ACTION_WIDGET_FAVORITE -> {
                playbackState.value.currentSong?.let { toggleFavorite(it) }
            }
        }
        com.example.widget.WidgetUpdateManager.updateAllWidgets(getApplication(), playbackManager.playbackState.value)
    }

    fun toggleFavorite(song: Song) {
        val newFav = !song.isFavorite
        com.example.widget.WidgetUpdateManager.setFavoriteStateImmediate(getApplication(), song.id, newFav)
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
        viewModelScope.launch(Dispatchers.IO) {
            val createdIds = repository.createPlaylistsWithSongsBatch(listOf(name to songs.map { it.id }))
            val playlistId = createdIds.firstOrNull()?.first ?: repository.createPlaylist(name)
            withContext(Dispatchers.Main) {
                onCreated(playlistId)
            }
        }
    }

    /**
     * Issue #6: Batch-imports multiple M3U playlists in a single background transaction.
     */
    fun createPlaylistsWithSongsBatch(
        playlistsBatch: List<Pair<String, List<Song>>>,
        onCompleted: (List<Long>) -> Unit = {}
    ) {
        if (playlistsBatch.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val batchPayload = playlistsBatch.map { (playlistName, songList) ->
                playlistName to songList.map { it.id }
            }
            val createdIds = repository.createPlaylistsWithSongsBatch(batchPayload).map { it.first }
            withContext(Dispatchers.Main) {
                onCompleted(createdIds)
            }
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

    suspend fun getPlaylistsContainingSong(songId: Long): List<com.example.data.model.Playlist> {
        return repository.getPlaylistsContainingSong(songId)
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
        viewModelScope.launch(Dispatchers.IO) {
            repository.addFolder(path, name, isExcluded)
            if (preferences.isOnboardingCompleted.first()) {
                scanLibrary()
            }
        }
    }

    fun removeFolder(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeFolder(path)
            if (preferences.isOnboardingCompleted.first()) {
                scanLibrary()
            }
        }
    }

    fun completeOnboarding() {
        _isOnboardingCompleted.value = true
        showSplash.value = false
        viewModelScope.launch(Dispatchers.IO) {
            preferences.setOnboardingCompleted(true)
            scanner.startAutoDetection()
        }
    }

    fun setSleepTimerMode(mode: String) {
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setSleepTimerMode(mode)
            }
        }
    }

    fun startSleepTimerMinutes(minutes: Int, finishLastSong: Boolean = sleepTimerFinishLastSong.value) {
        sleepTimerManager.startTimerMinutes(minutes, finishLastSong)
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setSleepTimerMode("TIME")
                preferences.setSleepTimerFinishLastSong(finishLastSong)
            }
        }
    }

    fun startSleepTimerSongs(count: Int) {
        playbackManager.startTrackSleepTimer(count)
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setSleepTimerMode("SONGS")
            }
        }
    }

    fun addSleepTimerMinutes(minutes: Int) = sleepTimerManager.addMinutes(minutes)

    fun setSleepTimerFinishLastSong(enabled: Boolean) {
        sleepTimerManager.setFinishLastSong(enabled)
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferences.setSleepTimerFinishLastSong(enabled)
            }
        }
    }

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
            cloudSyncRepository.onLyricsUpdatedAsync(song.id)
        }
    }


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
            cloudSyncRepository.onLyricsUpdatedAsync(song.id)
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
        newGenre: String? = null,
        newYear: Int? = null,
        newTrackNumber: Int? = null,
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
                context = getApplication(),
                newGenre = newGenre,
                newYear = newYear,
                newTrackNumber = newTrackNumber
            )
            val finalArt = updatedSong.artworkUri ?: com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(getApplication(), updatedSong.id, updatedSong.path)
            com.example.util.ArtworkManager.invalidateNotificationArtwork(updatedSong.id, updatedSong.path)
            if (!finalArt.isNullOrBlank()) {
                com.example.ui.components.ArtworkResolutionCache.notifyArtworkChanged(updatedSong.id, finalArt, updatedSong.path)
                try {
                    val bmp = com.example.util.ArtworkManager.decodeBitmapFromUri(getApplication(), finalArt, 512)
                    if (bmp != null) {
                        com.example.util.ArtworkManager.putCachedNotificationBitmap(updatedSong.id, bmp, updatedSong.path)
                        val stream = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, stream)
                        com.example.util.ArtworkManager.putNotificationArtwork(updatedSong.id, stream.toByteArray(), bmp, updatedSong.path)
                    }
                } catch (_: Throwable) {}
            } else {
                com.example.ui.components.ArtworkResolutionCache.notifyArtworkChanged(updatedSong.id, null, updatedSong.path)
            }
            com.example.widget.WidgetBitmapRenderer.invalidateSong(updatedSong.id)
            playbackManager.updateSongInQueue(updatedSong)
            if (!finalArt.isNullOrBlank()) {
                playbackManager.updateCurrentSongArtwork(updatedSong.id, finalArt, updatedSong.path)
            }
            com.example.widget.WidgetUpdateManager.updateAllWidgets(getApplication(), playbackState.value)
            com.example.service.MusicPlaybackService.instance?.triggerNotificationUpdate()
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
            if (newLyrics.isNotBlank()) {
                cloudSyncRepository.onLyricsUpdatedAsync(updatedSong.id)
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
        cloudSyncRepository.onLyricsOffsetChangedAsync(songId, offsetMs)
        cloudSyncRepository.onLyricsUpdatedAsync(songId)
    }

    // =========================================================================
    // AUDIO TRANSCRIBE & LYRICS TRANSLATION (GEMINI AI ENGINE)
    // =========================================================================

    fun setTranscribeTranslateEnabled(enabled: Boolean, onNeedsKey: (() -> Unit)? = null) = viewModelScope.launch {
        if (enabled) {
            if (!secureApiKeyStore.hasApiKey()) {
                preferences.setTranscribeTranslateEnabled(false)
                onNeedsKey?.invoke()
            } else {
                preferences.setTranscribeTranslateEnabled(true)
            }
        } else {
            preferences.setTranscribeTranslateEnabled(false)
        }
    }

    fun saveUserApiKey(rawKey: String, onResult: (Boolean, String?) -> Unit) = viewModelScope.launch {
        _isKeyValidating.value = true
        _keyValidationErrorMessage.value = null
        val validation = geminiLyricsService.validateApiKey(rawKey)
        if (validation.isSuccess) {
            val saved = secureApiKeyStore.saveApiKey(rawKey)
            if (saved) {
                preferences.setTranscribeTranslateEnabled(true)
                _hasUserApiKey.value = true
                _maskedApiKey.value = secureApiKeyStore.getMaskedApiKey()
                _isKeyValidating.value = false
                onResult(true, null)
            } else {
                _isKeyValidating.value = false
                val msg = "Unable to store API key securely on device."
                _keyValidationErrorMessage.value = msg
                onResult(false, msg)
            }
        } else {
            _isKeyValidating.value = false
            val ex = validation.exceptionOrNull()
            val msg = if (ex is java.io.IOException) {
                "Network error connecting to Gemini API. Please check your internet connection."
            } else {
                ex?.message ?: "Invalid Gemini API key. Please check your key and try again."
            }
            _keyValidationErrorMessage.value = msg
            if (ex !is java.io.IOException || !secureApiKeyStore.hasApiKey()) {
                preferences.setTranscribeTranslateEnabled(false)
            }
            onResult(false, msg)
        }
    }

    fun deleteUserApiKey() = viewModelScope.launch {
        secureApiKeyStore.deleteApiKey()
        preferences.setTranscribeTranslateEnabled(false)
        _hasUserApiKey.value = false
        _maskedApiKey.value = null
        _keyValidationErrorMessage.value = null
    }

    fun clearKeyValidationError() {
        _keyValidationErrorMessage.value = null
    }

    fun setAudioTranscribeMode(mode: String) = viewModelScope.launch {
        preferences.setAudioTranscribeMode(mode)
    }

    fun setAudioTranscribeTargetLang(langCode: String) = viewModelScope.launch {
        preferences.setAudioTranscribeTargetLang(langCode)
    }

    fun clearTranscribeError() {
        _transcribeErrorMessage.value = null
    }

    fun translateLyricsForSong(song: Song, targetLanguage: TranscribeLanguage) = viewModelScope.launch(Dispatchers.IO) {
        if (_isTranscribingLyrics.value) return@launch

        val apiKey = secureApiKeyStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                _transcribeErrorMessage.value = "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                preferences.setTranscribeTranslateEnabled(false)
                _isTranscribingLyrics.value = false
                _transcribeStatusMessage.value = null
            }
            return@launch
        }

        val connectionCheck = geminiLyricsService.validateApiKey(apiKey)
        if (connectionCheck.isFailure) {
            val ex = connectionCheck.exceptionOrNull()
            withContext(Dispatchers.Main) {
                if (ex is java.io.IOException) {
                    _transcribeErrorMessage.value = "Network error connecting to Gemini API. Please check your internet connection."
                } else {
                    _transcribeErrorMessage.value = "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                    preferences.setTranscribeTranslateEnabled(false)
                }
                _isTranscribingLyrics.value = false
                _transcribeStatusMessage.value = null
            }
            return@launch
        }

        val current = _currentLyrics.value
        if (current == null || (current.plainLyrics.isNullOrBlank() && current.syncedLyricsJson.isNullOrBlank())) {
            withContext(Dispatchers.Main) {
                _transcribeErrorMessage.value = "Lyrics are required for translation. Please use Transcribe Audio or add lyrics for this track."
                _isTranscribingLyrics.value = false
                _transcribeStatusMessage.value = null
            }
            return@launch
        }

        withContext(Dispatchers.Main) {
            _isTranscribingLyrics.value = true
            _transcribeStatusMessage.value = "Translating lyrics to ${targetLanguage.englishName}..."
            _transcribeErrorMessage.value = null
        }

        try {
            val sourceHash = LyricsTranslationCache.computeSourceLyricsHash(current.plainLyrics, current.syncedLyricsJson)
            val cached = LyricsTranslationCache.get(
                context = getApplication(),
                songId = song.id,
                mode = AudioTranscribeMode.TRANSLATE,
                targetLang = targetLanguage,
                sourceLyricsHash = sourceHash
            )

            val (plainResult, syncedJsonResult) = if (cached != null) {
                Pair(cached.plainLyrics, cached.syncedLyricsJson)
            } else {
                val inputLrc = if (!current.syncedLyricsJson.isNullOrBlank()) {
                    com.example.util.LrcParser.formatSyncedJsonToLrc(current.syncedLyricsJson) ?: (current.plainLyrics ?: "")
                } else {
                    current.plainLyrics ?: ""
                }

                val result = geminiLyricsService.translateLyrics(inputLrc, targetLanguage, apiKey)
                if (result.isFailure) {
                    val ex = result.exceptionOrNull()
                    val err = if (ex is SecurityException) {
                        preferences.setTranscribeTranslateEnabled(false)
                        "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                    } else if (ex is java.io.IOException) {
                        "Network error connecting to Gemini API. Please check your internet connection."
                    } else {
                        ex?.message ?: "Lyrics translation failed."
                    }
                    withContext(Dispatchers.Main) {
                        _transcribeErrorMessage.value = err
                        _isTranscribingLyrics.value = false
                        _transcribeStatusMessage.value = null
                    }
                    return@launch
                }
                val res = result.getOrThrow()
                LyricsTranslationCache.put(
                    context = getApplication(),
                    songId = song.id,
                    mode = AudioTranscribeMode.TRANSLATE,
                    targetLang = targetLanguage,
                    sourceLyricsHash = sourceHash,
                    plainLyrics = res.first,
                    syncedLyricsJson = res.second
                )
                res
            }

            if (plainResult.isBlank() && syncedJsonResult.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    _transcribeErrorMessage.value = "Translation returned an empty result."
                    _isTranscribingLyrics.value = false
                    _transcribeStatusMessage.value = null
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                _transcribeLyricsPreview.value = com.example.service.transcription.TranscribeLyricsPreview(
                    song = song,
                    mode = AudioTranscribeMode.TRANSLATE,
                    targetLanguage = targetLanguage,
                    plainLyrics = plainResult,
                    syncedLyricsJson = syncedJsonResult,
                    source = "Translated (${targetLanguage.englishName})"
                )
                _isTranscribingLyrics.value = false
                _transcribeStatusMessage.value = null
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (e is SecurityException) {
                    _transcribeErrorMessage.value = "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                    preferences.setTranscribeTranslateEnabled(false)
                } else if (e is java.io.IOException) {
                    _transcribeErrorMessage.value = "Network error connecting to Gemini API. Please check your internet connection."
                } else {
                    _transcribeErrorMessage.value = e.message ?: "Translation error occurred"
                }
                _isTranscribingLyrics.value = false
                _transcribeStatusMessage.value = null
            }
        }
    }

    fun transcribeAudioForSong(
        song: Song,
        targetLanguage: TranscribeLanguage
    ) = viewModelScope.launch(Dispatchers.IO) {
        if (_isTranscribingLyrics.value) return@launch

        val apiKey = secureApiKeyStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                _transcribeErrorMessage.value = "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                preferences.setTranscribeTranslateEnabled(false)
                _isTranscribingLyrics.value = false
                _transcribeStatusMessage.value = null
            }
            return@launch
        }

        val connectionCheck = geminiLyricsService.validateApiKey(apiKey)
        if (connectionCheck.isFailure) {
            val ex = connectionCheck.exceptionOrNull()
            withContext(Dispatchers.Main) {
                if (ex is java.io.IOException) {
                    _transcribeErrorMessage.value = "Network error connecting to Gemini API. Please check your internet connection."
                } else {
                    _transcribeErrorMessage.value = "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                    preferences.setTranscribeTranslateEnabled(false)
                }
                _isTranscribingLyrics.value = false
                _transcribeStatusMessage.value = null
            }
            return@launch
        }

        withContext(Dispatchers.Main) {
            _isTranscribingLyrics.value = true
            _transcribeStatusMessage.value = "Transcribing vocals from audio..."
            _transcribeErrorMessage.value = null
        }

        try {
            val cached = LyricsTranslationCache.get(
                context = getApplication(),
                songId = song.id,
                mode = AudioTranscribeMode.TRANSCRIBE,
                targetLang = targetLanguage,
                sourceLyricsHash = null
            )

            val (plainResult, syncedJsonResult) = if (cached != null) {
                Pair(cached.plainLyrics, cached.syncedLyricsJson)
            } else {
                val targetFile = File(song.path)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    withContext(Dispatchers.Main) {
                        _transcribeErrorMessage.value = "Audio file not accessible: ${song.displayTitle}"
                        _isTranscribingLyrics.value = false
                        _transcribeStatusMessage.value = null
                    }
                    return@launch
                }

                val result = geminiLyricsService.transcribeAudio(targetFile, song, targetLanguage, apiKey)
                if (result.isFailure) {
                    val ex = result.exceptionOrNull()
                    val err = if (ex is SecurityException) {
                        preferences.setTranscribeTranslateEnabled(false)
                        "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                    } else if (ex is java.io.IOException) {
                        "Network error connecting to Gemini API. Please check your internet connection."
                    } else {
                        ex?.message ?: "Audio transcription failed."
                    }
                    withContext(Dispatchers.Main) {
                        _transcribeErrorMessage.value = err
                        _isTranscribingLyrics.value = false
                        _transcribeStatusMessage.value = null
                    }
                    return@launch
                }
                val res = result.getOrThrow()
                LyricsTranslationCache.put(
                    context = getApplication(),
                    songId = song.id,
                    mode = AudioTranscribeMode.TRANSCRIBE,
                    targetLang = targetLanguage,
                    sourceLyricsHash = null,
                    plainLyrics = res.first,
                    syncedLyricsJson = res.second
                )
                res
            }

            if (plainResult.isBlank() && syncedJsonResult.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    _transcribeErrorMessage.value = "Transcription returned an empty result."
                    _isTranscribingLyrics.value = false
                    _transcribeStatusMessage.value = null
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                _transcribeLyricsPreview.value = com.example.service.transcription.TranscribeLyricsPreview(
                    song = song,
                    mode = AudioTranscribeMode.TRANSCRIBE,
                    targetLanguage = targetLanguage,
                    plainLyrics = plainResult,
                    syncedLyricsJson = syncedJsonResult,
                    source = "Transcribed (${targetLanguage.englishName})"
                )
                _isTranscribingLyrics.value = false
                _transcribeStatusMessage.value = null
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (e is SecurityException) {
                    _transcribeErrorMessage.value = "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                    preferences.setTranscribeTranslateEnabled(false)
                } else if (e is java.io.IOException) {
                    _transcribeErrorMessage.value = "Network error connecting to Gemini API. Please check your internet connection."
                } else {
                    _transcribeErrorMessage.value = e.message ?: "Transcription error occurred"
                }
                _isTranscribingLyrics.value = false
                _transcribeStatusMessage.value = null
            }
        }
    }

    fun saveTranscribedLyrics(preview: com.example.service.transcription.TranscribeLyricsPreview) = viewModelScope.launch(Dispatchers.IO) {
        previousLyricsHistory[preview.song.id] = _currentLyrics.value

        val entity = com.example.data.local.LyricsEntity(
            songId = preview.song.id,
            plainLyrics = preview.plainLyrics,
            syncedLyricsJson = preview.syncedLyricsJson,
            isCached = true,
            source = preview.source
        )
        db.lyricsDao().insertLyrics(entity)
        cloudSyncRepository.onLyricsUpdatedAsync(preview.song.id)

        val lrcContent = if (!preview.syncedLyricsJson.isNullOrBlank()) {
            com.example.util.LrcParser.formatSyncedJsonToLrc(preview.syncedLyricsJson) ?: preview.plainLyrics
        } else {
            preview.plainLyrics
        }

        val rawTitle = preview.song.title.ifBlank { preview.song.displayTitle }
        val safeFileName = rawTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "song_${preview.song.id}" }
        val lrcFileName = "$safeFileName.lrc"

        try {
            if (preview.song.path.isNotBlank()) {
                val audioFile = java.io.File(preview.song.path)
                val parentDir = audioFile.parentFile
                if (parentDir != null && parentDir.exists()) {
                    try {
                        val companionNamed = java.io.File(parentDir, lrcFileName)
                        companionNamed.writeText(lrcContent, Charsets.UTF_8)
                        val companionAudio = java.io.File(parentDir, "${audioFile.nameWithoutExtension}.lrc")
                        if (companionAudio.name != companionNamed.name) {
                            companionAudio.writeText(lrcContent, Charsets.UTF_8)
                        }
                        android.media.MediaScannerConnection.scanFile(
                            getApplication(),
                            arrayOf(companionNamed.absolutePath),
                            arrayOf("text/plain", "application/x-lrc"),
                            null
                        )
                    } catch (_: Exception) {}
                }
            }

            try {
                val publicMusicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                val publicLyricsFolder = java.io.File(publicMusicDir, "Lyrics")
                if (!publicLyricsFolder.exists()) publicLyricsFolder.mkdirs()
                val publicLrcFile = java.io.File(publicLyricsFolder, lrcFileName)
                publicLrcFile.writeText(lrcContent, Charsets.UTF_8)
                android.media.MediaScannerConnection.scanFile(
                    getApplication(),
                    arrayOf(publicLrcFile.absolutePath),
                    arrayOf("text/plain", "application/x-lrc"),
                    null
                )
            } catch (_: Exception) {}

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    val resolver = getApplication<android.app.Application>().contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, lrcFileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MUSIC}/Lyrics")
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { os ->
                            os.write(lrcContent.toByteArray(Charsets.UTF_8))
                        }
                    }
                } catch (_: Exception) {}
            }

            try {
                val appLyricsDir = getApplication<android.app.Application>().getExternalFilesDir("Lyrics")
                if (appLyricsDir != null) {
                    if (!appLyricsDir.exists()) appLyricsDir.mkdirs()
                    java.io.File(appLyricsDir, lrcFileName).writeText(lrcContent, Charsets.UTF_8)
                }
            } catch (_: Exception) {}

            try {
                val cacheLyricsFile = getApplication<android.app.Application>().cacheDir.resolve("lyrics").resolve("${preview.song.id}.lrc")
                cacheLyricsFile.parentFile?.mkdirs()
                cacheLyricsFile.writeText(lrcContent, Charsets.UTF_8)
            } catch (_: Exception) {}

            try {
                com.example.util.AudioTagWriter.writeAudioTags(
                    context = getApplication(),
                    songPath = preview.song.path,
                    contentUriString = preview.song.contentUriString,
                    songId = preview.song.id,
                    newTitle = preview.song.title,
                    newArtist = preview.song.artist,
                    newAlbum = preview.song.album,
                    newArtworkUri = preview.song.artworkUri,
                    newLyrics = lrcContent
                )
            } catch (_: Exception) {}

        } catch (e: Exception) {
            android.util.Log.w("MusicViewModel", "Could not write local .lrc file: ${e.message}")
        }

        withContext(Dispatchers.Main) {
            if (playbackState.value.currentSong?.id == preview.song.id) {
                _currentLyrics.value = entity.toDomainModel()
            }
            _transcribeLyricsPreview.value = null
            android.widget.Toast.makeText(
                getApplication(),
                "Saved lyrics to local device as $lrcFileName",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    fun discardTranscribedLyrics() {
        _transcribeLyricsPreview.value = null
    }

    fun undoLyricsChanges(songId: Long) = viewModelScope.launch(Dispatchers.IO) {
        if (!previousLyricsHistory.containsKey(songId)) return@launch
        val previous = previousLyricsHistory.remove(songId)

        if (previous == null || (previous.plainLyrics.isNullOrBlank() && previous.syncedLyricsJson.isNullOrBlank())) {
            db.lyricsDao().deleteLyrics(songId)
            withContext(Dispatchers.Main) {
                if (playbackState.value.currentSong?.id == songId) {
                    _currentLyrics.value = null
                    _canUndoLyricsForCurrentSong.value = false
                }
            }
        } else {
            val entity = com.example.data.local.LyricsEntity(
                songId = songId,
                plainLyrics = previous.plainLyrics,
                syncedLyricsJson = previous.syncedLyricsJson,
                isCached = previous.isCached,
                source = previous.source
            )
            db.lyricsDao().insertLyrics(entity)
            cloudSyncRepository.onLyricsUpdatedAsync(songId)
            withContext(Dispatchers.Main) {
                if (playbackState.value.currentSong?.id == songId) {
                    _currentLyrics.value = entity.toDomainModel()
                    _canUndoLyricsForCurrentSong.value = false
                }
            }
        }
    }

    suspend fun getWrappedDataForPeriod(startTime: Long, endTime: Long = 9223372036854775807L): WrappedData {
        return repository.getWrappedDataForPeriod(startTime, endTime)
    }

    override fun onCleared() {
        audioRouteManager.unregister()
        super.onCleared()
    }
}
