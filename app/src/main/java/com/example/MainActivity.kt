package com.example

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.Song
import com.example.ui.components.AppUpdateDialog
import com.example.ui.components.FloatingPillNavBar
import com.example.ui.components.MiniPlayer
import com.example.ui.components.NavTab
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.MusicViewModel
import com.example.update.UpdateCheckResult
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val pendingJamInviteCode = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val pendingJamRecommendation = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)

    companion object {
        @Volatile
        var isActivityAlive: Boolean = false

        @Volatile
        private var currentActivity: MainActivity? = null

        fun deliverForegroundJamRecommendation(sessionId: String, recommendationId: String): Boolean {
            val activity = currentActivity ?: return false
            if (!isActivityAlive) return false
            activity.runOnUiThread { activity.pendingJamRecommendation.value = sessionId to recommendationId }
            return true
        }
    }

    private var onPermissionResultCallback: (() -> Unit)? = null

    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        onPermissionResultCallback?.invoke()
        if (permissions.values.any { it }) {
            try {
                val viewModel = androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java]
                if (viewModel.isOnboardingCompleted.value && !viewModel.showSplash.value) {
                    viewModel.scanLibrary()
                }
            } catch (_: Exception) {}
        }
    }

    private var mediaStoreObserver: android.database.ContentObserver? = null
    private val openNowPlayingRequest = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val pendingWidgetActionRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        isActivityAlive = true
        super.onCreate(savedInstanceState)
        currentActivity = this
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        handleWidgetLaunchIntent(intent)
        handleJamInviteIntent(intent)
        handleJamRecommendationIntent(intent)

        setContent {
            val viewModel: MusicViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val uiTheme by viewModel.uiTheme.collectAsStateWithLifecycle()
            val accentColorPreset by viewModel.accentColorPreset.collectAsStateWithLifecycle()
            val customAccentColor by viewModel.customAccentColor.collectAsStateWithLifecycle()
            val isDynamicArtworkTheme by viewModel.isDynamicArtworkTheme.collectAsStateWithLifecycle()
            val isGradientEffect by viewModel.isGradientEffect.collectAsStateWithLifecycle()
            val isGlassEffect by viewModel.isGlassEffect.collectAsStateWithLifecycle()
            val isGradientGlassEffect by viewModel.isGradientGlassEffect.collectAsStateWithLifecycle()
            val dynamicArtworkColor by viewModel.dynamicArtworkColor.collectAsStateWithLifecycle()

            val effectiveAccentColor: Color? = when {
                isDynamicArtworkTheme && dynamicArtworkColor != null -> dynamicArtworkColor
                customAccentColor != 0 -> Color(customAccentColor)
                else -> null
            }

            val displayScale by viewModel.displayScale.collectAsStateWithLifecycle()
            val currentDensity = androidx.compose.ui.platform.LocalDensity.current
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val baseDensityFactor = remember(
                currentDensity.density,
                currentDensity.fontScale,
                configuration.screenWidthDp,
                configuration.screenHeightDp,
                 configuration.smallestScreenWidthDp,
                displayScale
            ) {
                val rawShortestDp = configuration.smallestScreenWidthDp.takeIf { it > 0 }?.toFloat()
                    ?: minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat().coerceAtLeast(320f)

                // Ideal reference baseline from the target phone layout (~412dp effective shortest width).
                // Phones (< 580dp shortest width) normalize toward 412dp so small phones (320-384dp),
                // standard phones (390-412dp), and large phones (430-480dp) render with identical,
                // proportional UI sizing and zero clipping. Foldables (580-839dp) and tablets (840dp+)
                // use a balanced ergonomic scale while preserving responsive multi-pane/max-width layouts.
                val deviceAdaptiveFactor = when {
                    rawShortestDp < 580f -> (rawShortestDp / 412f).coerceIn(0.76f, 1.15f)
                    rawShortestDp < 840f -> 1.04f
                    else -> 1.08f
                }

                val userScaleMultiplier = when (displayScale.uppercase()) {
                    "COMFORT" -> 1.08f
                    "COMPACT" -> 0.90f
                    else -> 1.0f // DEFAULT matches the 412dp reference screenshots
                }

                val safeDensity = (currentDensity.density * deviceAdaptiveFactor * userScaleMultiplier).coerceIn(0.75f, 5.0f)
                // Compose sp.toPx() = sp * density * fontScale, so density already scales sp proportionally.
                // Clamp system fontScale to a safe range so extreme OS font settings never clip single-line UI.
                val safeFontScale = currentDensity.fontScale.coerceIn(0.92f, 1.06f)
                androidx.compose.ui.unit.Density(
                    density = safeDensity,
                    fontScale = safeFontScale
                )
            }

            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides baseDensityFactor
            ) {
                MyApplicationTheme(
                    uiTheme = uiTheme,
                    themeMode = themeMode,
                    accentColorPreset = accentColorPreset,
                    accentColor = effectiveAccentColor,
                    isGradientGlassEffect = isGradientGlassEffect,
                    isGradientEffect = isGradientEffect,
                    isGlassEffect = isGlassEffect,
                    dynamicArtworkColor = dynamicArtworkColor
                ) {
                    val showSplash by viewModel.showSplash.collectAsStateWithLifecycle()
                    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsStateWithLifecycle()

                    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
                    val jamInviteCode by pendingJamInviteCode.collectAsStateWithLifecycle()
                    val jamRecommendation by pendingJamRecommendation.collectAsStateWithLifecycle()
                    var showJamLinkSettingsPrompt by remember { mutableStateOf(false) }
                    val showNowPlaying by viewModel.showNowPlaying.collectAsStateWithLifecycle()
                    val nowPlayingIntentReq by openNowPlayingRequest.collectAsStateWithLifecycle()
                    val widgetActionReq by pendingWidgetActionRequest.collectAsStateWithLifecycle()

                    LaunchedEffect(nowPlayingIntentReq) {
                        if (nowPlayingIntentReq > 0L) {
                            viewModel.dismissSplash()
                            viewModel.showNowPlaying.value = true
                            openNowPlayingRequest.value = 0L
                        }
                    }

                    LaunchedEffect(widgetActionReq) {
                        val action = widgetActionReq
                        if (!action.isNullOrBlank()) {
                            viewModel.dismissSplash()
                            viewModel.enqueueOrExecuteWidgetAction(action)
                            pendingWidgetActionRequest.value = null
                        }
                    }
                    LaunchedEffect(jamInviteCode) {
                        val code = jamInviteCode ?: return@LaunchedEffect
                        viewModel.dismissSplash()
                        viewModel.selectedTab.value = NavTab.JAM
                        val needsAuth = viewModel.jamController?.requestJoin(code) ?: false
                        if (needsAuth) viewModel.openAuthScreen()
                        pendingJamInviteCode.value = null
                    }
                    LaunchedEffect(jamRecommendation) {
                        val pending = jamRecommendation ?: return@LaunchedEffect
                        viewModel.dismissSplash()
                        viewModel.selectedTab.value = NavTab.JAM
                        val needsAuth = viewModel.jamController?.requestOpenRecommendation(pending.first, pending.second) ?: false
                        if (needsAuth) viewModel.openAuthScreen()
                        pendingJamRecommendation.value = null
                    }
                    LaunchedEffect(selectedTab, showSplash) {
                        showJamLinkSettingsPrompt = !showSplash &&
                            selectedTab == NavTab.JAM &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            !isJamLinkHandlingEnabled()
                    }
                    if (showJamLinkSettingsPrompt) {
                        AlertDialog(
                            onDismissRequest = { showJamLinkSettingsPrompt = false },
                            title = { Text("Open Jam links in OffGrid Music?") },
                            text = {
                                Text(
                                    "Android is not set to open ${BuildConfig.JAM_INVITE_HOST} Jam links in OffGrid Music. " +
                                        "You can enable Open supported links in the app settings. Jam invitation codes will still work if you skip this."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showJamLinkSettingsPrompt = false
                                    openJamLinkSettings()
                                }) {
                                    Text("Open link settings")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showJamLinkSettingsPrompt = false }) {
                                    Text("Not now")
                                }
                            }
                        )
                    }
                    val searchTabQuery by viewModel.searchTabQuery.collectAsStateWithLifecycle()
                    val librarySearchQuery by viewModel.librarySearchQuery.collectAsStateWithLifecycle()
                    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

                    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
                    val favoriteSongs by viewModel.favoriteSongs.collectAsStateWithLifecycle()
                    val recentlyPlayed by viewModel.recentlyPlayedSongs.collectAsStateWithLifecycle()
                    val recentlyAdded by viewModel.recentlyAddedSongs.collectAsStateWithLifecycle()
                    val allFolders by viewModel.allFolders.collectAsStateWithLifecycle()
                    val ignoreAudioDuration by viewModel.ignoreAudioDuration.collectAsStateWithLifecycle()
                    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
                    val mostPlayedPlaylists by viewModel.mostPlayedPlaylists.collectAsStateWithLifecycle()
                    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
                    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
                    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
                    val currentLyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
                    val currentAudioRoute by viewModel.currentAudioRoute.collectAsStateWithLifecycle()
                    val librarySortOption by viewModel.librarySortOption.collectAsStateWithLifecycle()
                    val librarySortAscending by viewModel.librarySortAscending.collectAsStateWithLifecycle()
                    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
                    val homeScreenLayout by viewModel.homeScreenLayout.collectAsStateWithLifecycle()
                    val speedDialSongs by viewModel.speedDialSongs.collectAsStateWithLifecycle()
                    val homeSections by viewModel.homeSections.collectAsStateWithLifecycle()
                    val nowPlayingLayout by viewModel.nowPlayingLayout.collectAsStateWithLifecycle()
                    val playlistsLayout by viewModel.playlistsLayout.collectAsStateWithLifecycle()

                    val userAccount by viewModel.userAccount.collectAsStateWithLifecycle()
                    val effectiveDisplayName by viewModel.effectiveDisplayName.collectAsStateWithLifecycle()
                    val customAvatarUri by viewModel.customAvatarUri.collectAsStateWithLifecycle()
                    val customDisplayName by viewModel.customDisplayName.collectAsStateWithLifecycle()
                    val showAuthScreen by viewModel.showAuthScreen.collectAsStateWithLifecycle()
                    val showAccountScreen by viewModel.showAccountScreen.collectAsStateWithLifecycle()
                    val authUiState by viewModel.authUiState.collectAsStateWithLifecycle()
                    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
                    val updateCheckResult by viewModel.updateCheckResult.collectAsStateWithLifecycle()
                    val forceShowUpdateDialog by viewModel.forceShowUpdateDialog.collectAsStateWithLifecycle()

                    // Onboarding state is resolved at startup via UserPreferences & AppDatabase upgrade detection.

                    var backPressedTime by remember { mutableLongStateOf(0L) }
                    var songForAddToPlaylist by remember { mutableStateOf<com.example.data.model.Song?>(null) }
                    var songForTagEditing by remember { mutableStateOf<com.example.data.model.Song?>(null) }
                    var tagEditingLyricsText by remember { mutableStateOf("") }

                    var showStoragePermissionDialog by remember { mutableStateOf(false) }
                    var storagePermissionDialogTitle by remember { mutableStateOf("Storage Permission Required") }
                    var storagePermissionDialogText by remember { mutableStateOf("") }
                    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }

                    val executeDeleteSong: (com.example.data.model.Song) -> Unit = { song ->
                        if (!com.example.util.StoragePermissionHelper.hasStoragePermission(this@MainActivity)) {
                            storagePermissionDialogTitle = "Storage Permission Required"
                            storagePermissionDialogText = "OffGrid Music requires storage permission to permanently delete \"${song.displayTitle}\" from your local device storage.\n\nPlease grant permission to apply this change to both your device storage and app library."
                            pendingStorageAction = {
                                viewModel.deleteSong(song, this@MainActivity)
                            }
                            showStoragePermissionDialog = true
                        } else {
                            viewModel.deleteSong(song, this@MainActivity)
                        }
                    }

                    LaunchedEffect(songForTagEditing?.id) {
                        val target = songForTagEditing
                        if (target != null) {
                            tagEditingLyricsText = ""
                            tagEditingLyricsText = viewModel.getLyricsTextForSong(target)
                        } else {
                            tagEditingLyricsText = ""
                        }
                    }

                    androidx.activity.compose.BackHandler(enabled = !showSplash && isOnboardingCompleted) {
                        val selectedPlaylistVal = viewModel.selectedPlaylist.value
                        val selectedSmartVal = viewModel.selectedSmartPlaylist.value
                        if (showNowPlaying) {
                            viewModel.showNowPlaying.value = false
                        } else if (showAuthScreen) {
                            viewModel.closeAuthScreen()
                        } else if (showAccountScreen) {
                            viewModel.closeAccountScreen()
                        } else if (selectedPlaylistVal != null) {
                            viewModel.selectPlaylist(null)
                        } else if (selectedSmartVal != null) {
                            viewModel.selectSmartPlaylist(null)
                        } else if (searchTabQuery.isNotEmpty()) {
                            viewModel.searchTabQuery.value = ""
                        } else if (librarySearchQuery.isNotEmpty()) {
                            viewModel.librarySearchQuery.value = ""
                        } else if (selectedTab != NavTab.HOME) {
                            if (!viewModel.navigateBackTab()) {
                                viewModel.selectedTab.value = NavTab.HOME
                            }
                        } else {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - backPressedTime < 2000) {
                                finish()
                            } else {
                                backPressedTime = currentTime
                                android.widget.Toast.makeText(this@MainActivity, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    var hasStorageAccess by remember { mutableStateOf(hasAllFilesPermission()) }

                    DisposableEffect(Unit) {
                        onPermissionResultCallback = {
                            hasStorageAccess = hasAllFilesPermission()
                        }
                        val listener = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                hasStorageAccess = hasAllFilesPermission()
                                val currentTab = viewModel.selectedTab.value
                                if (!viewModel.showSplash.value &&
                                    viewModel.isOnboardingCompleted.value &&
                                    (currentTab == NavTab.HOME || currentTab == NavTab.LIBRARY)
                                ) {
                                    viewModel.onScreenEnteredForAutoScan(currentTab.name)
                                    if (currentTab == NavTab.HOME) {
                                        viewModel.checkForUpdates(isManual = false)
                                    }
                                }
                            }
                        }
                        val lifecycle = this@MainActivity.lifecycle
                        lifecycle.addObserver(listener)
                        onDispose {
                            if (onPermissionResultCallback != null) {
                                onPermissionResultCallback = null
                            }
                            lifecycle.removeObserver(listener)
                        }
                    }

                    // 1. Startup Animation (SplashScreen) ALWAYS displays FIRST in complete isolation
                    if (showSplash) {
                        com.example.ui.components.SplashScreen(
                            onDismiss = { viewModel.dismissSplash() }
                        )
                    } else if (!isOnboardingCompleted) {
                        // 2. Once Startup Animation finishes, display Onboarding Screen options for fresh installations
                        val fullDeviceScanSetting by viewModel.fullDeviceScan.collectAsState()
                        val maxSongDurationSetting by viewModel.maxSongDuration.collectAsState()

                        OnboardingScreen(
                            scanProgress = scanProgress,
                            hasAllFilesAccess = hasStorageAccess,
                            folders = allFolders,
                            ignoreDurationSec = ignoreAudioDuration,
                            maxDurationSec = maxSongDurationSetting,
                            fullDeviceScan = fullDeviceScanSetting,
                            onSetFullDeviceScan = { viewModel.setFullDeviceScan(it) },
                            onSetIgnoreAudioDuration = { min -> viewModel.setIgnoreAudioDuration(min) },
                            onSetSongDurationRange = { min, max -> viewModel.setSongDurationRange(min, max) },
                            onAddFolder = { path, name, isExcluded -> viewModel.addFolder(path, name, isExcluded) },
                            onRemoveFolder = { viewModel.removeFolder(it) },
                            onRequestStoragePermission = { requestAllFilesPermission() },
                            onStartScan = { isFull -> viewModel.scanLibrary(isFull) },
                            onComplete = { viewModel.completeOnboarding() }
                        )
                    } else {
                        // 3. Main App UI (Shown after Startup Animation finishes and Onboarding is complete)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BentoBackground)
                        ) {
                            com.example.ui.theme.GlassGradientBackdrop()
                            val highRefreshRateSetting by viewModel.highRefreshRate.collectAsStateWithLifecycle()
                            LaunchedEffect(highRefreshRateSetting) {
                                applyHighRefreshRate(highRefreshRateSetting)
                            }

                            LaunchedEffect(Unit) {
                                com.example.ui.components.ArtworkResolutionCache.ensureDiskIndexWarm(this@MainActivity)
                                checkPermissions()
                                kotlinx.coroutines.delay(1500)
                                if (viewModel.allSongs.value.isEmpty()) {
                                    triggerQuickScan()
                                }
                            }

                            val selectedPlaylist by viewModel.selectedPlaylist.collectAsStateWithLifecycle()
                            val selectedSmartPlaylist by viewModel.selectedSmartPlaylist.collectAsStateWithLifecycle()
                            if (selectedTab == NavTab.HOME &&
                                selectedPlaylist == null &&
                                selectedSmartPlaylist == null &&
                                !showAccountScreen &&
                                !showAuthScreen &&
                                com.example.ui.theme.HomeScreenLayout.fromId(homeScreenLayout) == com.example.ui.theme.HomeScreenLayout.HERO_SPOTLIGHT
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(400.dp)
                                        .background(
                                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                                0.0f to BentoPrimary.copy(alpha = 0.22f),
                                                0.45f to BentoAccentPinkText.copy(alpha = 0.08f),
                                                1.0f to Color.Transparent
                                            )
                                        )
                                )
                            }
                            val smartPlaylists by viewModel.smartPlaylists.collectAsStateWithLifecycle()
                            val selectedPlaylistSongs by remember(selectedPlaylist) {
                                val cur = selectedPlaylist
                                if (cur != null) {
                                    viewModel.getPlaylistSongs(cur.id)
                                } else {
                                    kotlinx.coroutines.flow.flowOf(emptyList())
                                }
                            }.collectAsStateWithLifecycle(initialValue = emptyList())
                            val dailyStats7Days by remember { viewModel.getDailyListeningStats(7) }.collectAsStateWithLifecycle(initialValue = emptyList())

                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                containerColor = com.example.ui.theme.BentoScreenBackground,
                                bottomBar = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .navigationBarsPadding(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .widthIn(max = 680.dp)
                                                .fillMaxWidth()
                                        ) {
                                            // Issue #7 Requirement:
                                            // Enable the Mini-Player / floating bar while accessing the Settings screen
                                            // & Account screen, but hide the bottom navigation bar (Home, Library, Playlists, Search)
                                            // while in the Settings screen or Account screen.
                                            val isSettingsOrAccountActive = showAccountScreen ||
                                                (selectedTab == NavTab.SETTINGS && selectedPlaylist == null && selectedSmartPlaylist == null)
                                            val shouldShowMiniPlayer = playbackState.currentSong != null && !showAuthScreen
                                            val shouldShowBottomNavBar = !showAuthScreen && !isSettingsOrAccountActive

                                            if (shouldShowMiniPlayer) {
                                                val miniPlayerShowPrevious by viewModel.miniPlayerShowPrevious.collectAsStateWithLifecycle()
                                                val miniPlayerShowNext by viewModel.miniPlayerShowNext.collectAsStateWithLifecycle()
                                                val miniPlayerShowFavorite by viewModel.miniPlayerShowFavorite.collectAsStateWithLifecycle()
                                                val miniPlayerStyle by viewModel.miniPlayerStyle.collectAsStateWithLifecycle()

                                                MiniPlayer(
                                                    song = playbackState.currentSong,
                                                    isPlaying = playbackState.isPlaying,
                                                    progressMs = playbackState.positionMs,
                                                    durationMs = playbackState.durationMs,
                                                    playbackPositionFlow = viewModel.playbackPositionMs,
                                                    onTogglePlayPause = { viewModel.togglePlayPause() },
                                                    onSkipNext = { viewModel.skipToNext() },
                                                    onClick = { viewModel.showNowPlaying.value = true },
                                                    showPrevious = miniPlayerShowPrevious,
                                                    showNext = miniPlayerShowNext,
                                                    showFavorite = miniPlayerShowFavorite,
                                                    onSkipPrevious = { viewModel.skipToPrevious() },
                                                    onFavoriteToggle = { playbackState.currentSong?.let { viewModel.toggleFavorite(it) } },
                                                    style = miniPlayerStyle
                                                )
                                            }

                                            AnimatedVisibility(
                                                visible = shouldShowBottomNavBar,
                                                enter = slideInVertically(tween(200)) { it } + fadeIn(tween(180)),
                                                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(150))
                                            ) {
                                                FloatingPillNavBar(
                                                    selectedTab = selectedTab,
                                                    playbackState = playbackState,
                                                    onTabSelected = {
                                                        viewModel.closeAccountScreen()
                                                        viewModel.closeAuthScreen()
                                                        viewModel.selectPlaylist(null)
                                                        viewModel.selectSmartPlaylist(null)
                                                        viewModel.navigateToTab(it)
                                                    },
                                                    onTogglePlayPause = { viewModel.togglePlayPause() },
                                                    onOpenNowPlaying = { viewModel.showNowPlaying.value = true }
                                                )
                                            }
                                        }
                                    }
                                }
                            ) { innerPadding ->
                                val isDarkMainBg = (BentoBackground.red * 0.299f + BentoBackground.green * 0.587f + BentoBackground.blue * 0.114f) < 0.5f
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(innerPadding)
                                    ) {
                                        val currentPlaylist = selectedPlaylist
                                        val currentSmartPlaylist = selectedSmartPlaylist
                                        if (showAuthScreen) {
                                            val passwordResetState by viewModel.passwordResetState.collectAsState()
                                            com.example.ui.screens.AuthScreen(
                                                authUiState = authUiState,
                                                passwordResetState = passwordResetState,
                                                onSignInWithGoogle = { activity -> viewModel.signInWithGoogle(activity) },
                                                onSignInWithEmail = { e, p -> viewModel.signInWithEmail(e, p) },
                                                onSignUpWithEmail = { e, p, n -> viewModel.signUpWithEmail(e, p, n) },
                                                onForgotPassword = { e -> viewModel.sendPasswordReset(e) },
                                                onDismissPasswordReset = { viewModel.resetPasswordResetState() },
                                                onContinueWithoutSignUp = { viewModel.continueWithoutSignUp() },
                                                onDismissError = { viewModel.resetAuthUiState() },
                                                onBackClick = { viewModel.closeAuthScreen() }
                                            )
                                        } else if (showAccountScreen) {
                                            com.example.ui.screens.AccountScreen(
                                                userAccount = userAccount,
                                                customDisplayName = customDisplayName,
                                                customAvatarUri = customAvatarUri,
                                                effectiveDisplayName = effectiveDisplayName,
                                                syncStatus = syncStatus,
                                                backupStatus = viewModel.backupStatus.collectAsState().value,
                                                lastSyncedTimestamp = viewModel.lastSyncedTimestamp.collectAsState().value,
                                                lastBackupTimestamp = viewModel.lastBackupTimestamp.collectAsState().value,
                                                automaticBackupEnabled = viewModel.automaticBackupEnabled.collectAsState().value,
                                                backupFrequency = viewModel.backupFrequency.collectAsState().value,
                                                onToggleAutomaticBackup = { viewModel.setAutomaticBackupEnabled(it) },
                                                onSelectFrequency = { viewModel.setBackupFrequency(it) },
                                                onSyncNow = { viewModel.triggerCloudSync() },
                                                onBackupNow = { viewModel.triggerBackupNow() },
                                                onOpenAuth = { viewModel.openAuthScreen() },
                                                onSignInGoogle = { viewModel.signInWithGoogle(this@MainActivity) },
                                                onUpdateDisplayName = { viewModel.updateUserDisplayName(it) },
                                                onUpdateAvatar = { viewModel.updateProfileAvatar(it) },
                                                onSignOut = { viewModel.signOut() },
                                                onBackClick = { viewModel.closeAccountScreen() }
                                            )
                                        } else if (currentPlaylist != null) {
                                            com.example.ui.screens.PlaylistDetailScreen(
                                                playlist = currentPlaylist,
                                                playlistSongs = selectedPlaylistSongs,
                                                allPlaylists = allPlaylists,
                                            playbackState = playbackState,
                                            onBackClick = { viewModel.selectPlaylist(null) },
                                            onSongClick = { song, queue ->
                                                viewModel.recordPlaylistPlay(currentPlaylist.id)
                                                viewModel.playSong(song, queue, sourceContext = "PLAYLIST")
                                            },
                                            onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                            onRemoveSongFromPlaylist = { pId, sId -> viewModel.removeSongFromPlaylist(pId, sId) },
                                            onDeletePlaylist = { pId -> viewModel.deletePlaylist(pId) },
                                            onRemoveDuplicates = {
                                                viewModel.removeDuplicateSongsFromPlaylist(currentPlaylist.id) { removed ->
                                                    if (removed > 0) {
                                                        android.widget.Toast.makeText(this@MainActivity, "Removed $removed duplicate song${if (removed > 1) "s" else ""} from playlist", android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        android.widget.Toast.makeText(this@MainActivity, "No duplicate songs in playlist", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            onRenamePlaylist = { pId, newName -> viewModel.renamePlaylist(pId, newName) },
                                            onAddSongToPlaylist = { pId, sId ->
                                                viewModel.addSongToPlaylist(pId, sId) { added ->
                                                    if (!added) {
                                                        android.widget.Toast.makeText(this@MainActivity, "Already in playlist", android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        android.widget.Toast.makeText(this@MainActivity, "Added to playlist", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            onCreateNewPlaylist = { name -> viewModel.createPlaylist(name) },
                                            onUpdatePlaylistArtwork = { id, uri -> viewModel.updatePlaylistArtwork(id, uri) },
                                            onPlayNext = { viewModel.playNext(it) },
                                            onAddToQueue = { viewModel.addToQueue(it) },
                                            onShuffleAll = { queue ->
                                                viewModel.recordPlaylistPlay(currentPlaylist.id)
                                                viewModel.shuffleAndPlay(queue, sourceContext = "PLAYLIST")
                                            },
                                            onPlayAll = { displayedSongs ->
                                                viewModel.recordPlaylistPlay(currentPlaylist.id)
                                                viewModel.playAll(displayedSongs, sourceContext = "PLAYLIST")
                                            },
                                            onDeleteSong = { song -> executeDeleteSong(song) },
                                            onEditTags = { songForTagEditing = it },
                                            onReorderSongs = { updatedSongs ->
                                                viewModel.reorderPlaylistSongs(currentPlaylist.id, updatedSongs)
                                            }
                                        )
                                    } else if (currentSmartPlaylist != null) {
                                        com.example.ui.screens.SmartPlaylistDetailScreen(
                                            smartPlaylist = currentSmartPlaylist,
                                            allSongs = allSongs,
                                            playbackState = playbackState,
                                            onBackClick = { viewModel.selectSmartPlaylist(null) },
                                            onSongClick = { song, queue -> viewModel.playSong(song, queue) },
                                            onShufflePlay = { queue ->
                                                viewModel.shuffleAndPlay(queue, sourceContext = "SMART_PLAYLIST")
                                            },
                                            onPlayAll = { songs ->
                                                viewModel.playAll(songs, sourceContext = "SMART_PLAYLIST")
                                            },
                                            onDeleteSmartPlaylist = { id -> viewModel.deleteSmartPlaylist(id) },
                                            onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                            onPlayNext = { viewModel.playNext(it) },
                                            onAddToQueue = { viewModel.addToQueue(it) },
                                            onAddToPlaylist = { songForAddToPlaylist = it },
                                            onDeleteSong = { song -> executeDeleteSong(song) },
                                            onEditTags = { songForTagEditing = it }
                                        )
                                    } else {
                                        when (selectedTab) {
                                             NavTab.HOME -> HomeScreen(
                                                allSongs = allSongs,
                                                favoriteSongs = favoriteSongs,
                                                recentlyPlayed = recentlyPlayed,
                                                recentlyAdded = recentlyAdded,
                                                allPlaylists = allPlaylists,
                                                mostPlayedPlaylists = mostPlayedPlaylists,
                                                playbackState = playbackState,
                                                scanProgress = scanProgress,
                                                sleepTimerState = sleepTimerState,
                                                onSongClick = { viewModel.playSong(it) },
                                                onPlaySongQueue = { song, queue -> viewModel.playSong(song, queue) },
                                                onTogglePlayPause = { viewModel.togglePlayPause() },
                                                onQuickShuffle = { viewModel.shuffleAndPlay() },
                                                onScanClick = { viewModel.quickScanAndCleanLibrary() },
                                                onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                                onOpenNowPlaying = { viewModel.showNowPlaying.value = true },
                                                onOpenSettings = { section -> viewModel.openSettings(section) },
                                                onOpenFavorites = { viewModel.navigateToTab(NavTab.FAVORITES) },
                                                onOpenRecentlyPlayed = { viewModel.navigateToTab(NavTab.RECENTLY_PLAYED) },
                                                onOpenStatsDetail = { viewModel.navigateToTab(NavTab.STATS_DETAIL) },
                                                onOpenLibrary = { viewModel.navigateToTab(NavTab.LIBRARY) },
                                                onOpenPlaylists = { viewModel.navigateToTab(NavTab.PLAYLISTS) },
                                                onOpenSearch = { viewModel.navigateToTab(NavTab.SEARCH) },
                                                onSelectPlaylist = { viewModel.selectPlaylist(it) },
                                                onPlayNext = { viewModel.playNext(it) },
                                                onAddToQueue = { viewModel.addToQueue(it) },
                                                onAddToPlaylist = { songForAddToPlaylist = it },
                                                onDeleteSong = { song -> executeDeleteSong(song) },
                                                onEditTags = { songForTagEditing = it },
                                                getDurationPlayedBetween = { start, end -> viewModel.getTotalDurationPlayedBetween(start, end) },
                                                getTrackCountBetween = { start, end -> viewModel.getTrackCountBetween(start, end) },
                                                getUniqueTrackCountBetween = { start, end -> viewModel.getUniqueTrackCountBetween(start, end) },
                                                getMostPlayedSongsSince = { start, limit -> viewModel.getMostPlayedSongsBetween(start, limit = limit) },
                                                getTopArtistsSince = { start, limit -> viewModel.getTopArtistsBetween(start, limit = limit) },
                                                getTopAlbumsSince = { start, limit -> viewModel.getTopAlbumsBetween(start, limit = limit) },
                                                homeScreenLayout = homeScreenLayout,
                                                speedDialSongs = speedDialSongs,
                                                onRefreshSpeedDial = { viewModel.refreshSpeedDial() },
                                                homeSections = homeSections,
                                                playbackPositionFlow = viewModel.playbackPositionMs,
                                                userAccount = userAccount,
                                                effectiveDisplayName = effectiveDisplayName,
                                                customAvatarUri = customAvatarUri,
                                                onOpenAccount = { viewModel.openAccountScreen() },
                                                onOpenAuth = { viewModel.openAuthScreen() },
                                                onSignOut = { viewModel.signOut() },
                                                onScreenEntered = {
                                                    viewModel.onScreenEnteredForAutoScan("HOME")
                                                    viewModel.checkForUpdates(isManual = false)
                                                }
                                             )
                                            NavTab.LIBRARY -> LibraryScreen(
                                                allSongs = allSongs,
                                                playbackState = playbackState,
                                                searchQuery = librarySearchQuery,
                                                onSearchQueryChange = { viewModel.librarySearchQuery.value = it },
                                                onSongClick = { viewModel.playSong(it) },
                                                onPlayQueue = { queue, idx -> queue.getOrNull(idx)?.let { viewModel.playSong(it, queue) } },
                                                onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                                onBackClick = { if (!viewModel.navigateBackTab()) viewModel.selectedTab.value = NavTab.HOME },
                                                onOpenSettings = { viewModel.openSettings() },
                                                onOpenFavorites = { viewModel.navigateToTab(NavTab.FAVORITES) },
                                                onOpenSearch = { viewModel.navigateToTab(NavTab.SEARCH) },
                                                initialSortOption = librarySortOption,
                                                initialSortAscending = librarySortAscending,
                                                onSortChanged = { option, ascending -> viewModel.updateLibrarySort(option, ascending) },
                                                onPlayNext = { viewModel.playNext(it) },
                                                onAddToQueue = { viewModel.addToQueue(it) },
                                                onAddSongToPlaylist = { playlistId, songId ->
                                                    viewModel.addSongToPlaylist(playlistId, songId) { added ->
                                                        val msg = if (added) "Added to playlist" else "Already in playlist"
                                                        android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onCreateNewPlaylist = { name -> viewModel.createPlaylist(name) },
                                                onDeleteSong = { song -> executeDeleteSong(song) },
                                                onEditTags = { songForTagEditing = it },
                                                allPlaylists = allPlaylists,
                                                onGetPlaylistsContainingSong = { viewModel.getPlaylistsContainingSong(it) },
                                                onRemoveFromPlaylist = { playlistId, songId -> viewModel.removeSongFromPlaylist(playlistId, songId) },
                                                onShuffleAll = { queue -> viewModel.shuffleAndPlay(queue, sourceContext = "LIBRARY") },
                                                recentSearches = recentSearches,
                                                onAddRecentSearch = { viewModel.addRecentSearch(it) },
                                                onDeleteRecentSearch = { viewModel.deleteRecentSearch(it) },
                                                onClearRecentSearches = { viewModel.clearRecentSearches() },
                                                scanProgress = scanProgress,
                                                onScreenEntered = { viewModel.onScreenEnteredForAutoScan("LIBRARY") }
                                            )
                                            NavTab.PLAYLISTS -> {
                                                val playlistViewMode by viewModel.playlistViewMode.collectAsState()
                                                PlaylistsScreen(
                                                    playlists = allPlaylists,
                                                    mostPlayedPlaylists = mostPlayedPlaylists,
                                                    smartPlaylists = smartPlaylists,
                                                    allSongs = allSongs,
                                                    playlistViewMode = playlistViewMode,
                                                    onToggleViewMode = {
                                                        val nextMode = if (playlistViewMode == "GRID") "LIST" else "GRID"
                                                        viewModel.updatePlaylistViewMode(nextMode)
                                                    },
                                                    onCreatePlaylist = { viewModel.createPlaylist(it) },
                                                    onCreateSmartPlaylist = { viewModel.createSmartPlaylist(it) },
                                                    onPlaylistClick = { viewModel.selectPlaylist(it) },
                                                    onSmartPlaylistClick = { viewModel.selectSmartPlaylist(it) },
                                                    onPlayNow = { viewModel.playPlaylist(it.id) },
                                                    onPlayNext = { playlist ->
                                                        com.example.util.HapticUtils.performClickHaptic(this@MainActivity)
                                                        viewModel.playPlaylistNext(playlist.id) { count ->
                                                            android.widget.Toast.makeText(this@MainActivity, "Playing $count songs next", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    onAddToQueue = { playlist ->
                                                        com.example.util.HapticUtils.performClickHaptic(this@MainActivity)
                                                        viewModel.addPlaylistToQueue(playlist.id) { count ->
                                                            android.widget.Toast.makeText(this@MainActivity, "Added $count songs to queue", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    onRenamePlaylist = { pId, newName -> viewModel.renamePlaylist(pId, newName) },
                                                    onDeletePlaylist = { viewModel.deletePlaylist(it) },
                                                    onDeleteSmartPlaylist = { viewModel.deleteSmartPlaylist(it) },
                                                    onUpdatePlaylistArtwork = { id, uri -> viewModel.updatePlaylistArtwork(id, uri) },
                                                    onImportM3uPlaylist = { name, songs ->
                                                        viewModel.createPlaylistWithSongs(name, songs) { playlistId ->
                                                            val importedPlaylist = allPlaylists.find { it.id == playlistId }
                                                            viewModel.selectPlaylist(importedPlaylist ?: com.example.data.model.Playlist(id = playlistId, name = name, songCount = songs.size))
                                                        }
                                                    },
                                                    onImportMultipleM3uPlaylists = { batch ->
                                                        viewModel.createPlaylistsWithSongsBatch(batch)
                                                    },
                                                    onBackClick = { if (!viewModel.navigateBackTab()) viewModel.selectedTab.value = NavTab.HOME },
                                                    onOpenSearch = { viewModel.navigateToTab(NavTab.SEARCH) },
                                                    onOpenFavorites = { viewModel.navigateToTab(NavTab.FAVORITES) },
                                                    onOpenSettings = { viewModel.openSettings() },
                                                    playlistsLayout = playlistsLayout
                                                )
                                            }
                                            NavTab.SEARCH -> SearchScreen(
                                                searchQuery = searchTabQuery,
                                                onSearchQueryChange = { viewModel.searchTabQuery.value = it },
                                                allSongs = allSongs,
                                                playlists = allPlaylists,
                                                playbackState = playbackState,
                                                recentSearches = recentSearches,
                                                onAddRecentSearch = { viewModel.addRecentSearch(it) },
                                                onDeleteRecentSearch = { viewModel.deleteRecentSearch(it) },
                                                onClearRecentSearches = { viewModel.clearRecentSearches() },
                                                onSongClick = { viewModel.playSong(it) },
                                                onPlaySongQueue = { song, queue -> viewModel.playSong(song, queue) },
                                                onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                                onPlayNext = { viewModel.playNext(it) },
                                                onAddToQueue = { viewModel.addToQueue(it) },
                                                onAddToPlaylist = { songForAddToPlaylist = it },
                                                onDeleteSong = { song -> executeDeleteSong(song) },
                                                onEditTags = { songForTagEditing = it },
                                                onOpenSettings = { viewModel.openSettings() },
                                                onOpenFavorites = { viewModel.navigateToTab(NavTab.FAVORITES) },
                                                onBackClick = {
                                                    if (!viewModel.navigateBackTab()) {
                                                        viewModel.selectedTab.value = NavTab.HOME
                                                    }
                                                }
                                            )
                                            NavTab.FAVORITES -> FavoritesScreen(
                                                favoriteSongs = favoriteSongs,
                                                playbackState = playbackState,
                                                onSongClick = { viewModel.playSong(it, favoriteSongs, sourceContext = "FAVORITES") },
                                                onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                                onShuffleFavorites = {
                                                    if (favoriteSongs.isNotEmpty()) {
                                                        viewModel.shuffleAndPlay(favoriteSongs, sourceContext = "FAVORITES")
                                                    }
                                                },
                                                onPlayAll = {
                                                    if (favoriteSongs.isNotEmpty()) {
                                                        viewModel.playAll(favoriteSongs, sourceContext = "FAVORITES")
                                                    }
                                                },
                                                onClearAllFavorites = { viewModel.clearAllFavorites() },
                                                onExploreLibrary = { viewModel.navigateToTab(NavTab.LIBRARY) },
                                                onBackClick = {
                                                    if (!viewModel.navigateBackTab()) {
                                                        viewModel.selectedTab.value = NavTab.HOME
                                                    }
                                                },
                                                onPlayNext = { viewModel.playNext(it) },
                                                onAddToQueue = { viewModel.addToQueue(it) },
                                                onAddToPlaylist = { songForAddToPlaylist = it },
                                                onDeleteSong = { song -> executeDeleteSong(song) },
                                                onEditTags = { songForTagEditing = it }
                                            )
                                            NavTab.RECENTLY_PLAYED -> RecentlyPlayedScreen(
                                                recentlyPlayedSongs = recentlyPlayed,
                                                playbackState = playbackState,
                                                onSongClick = { song, queue -> viewModel.playSong(song, queue, sourceContext = "RECENTLY_PLAYED") },
                                                onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                                onShuffleAll = { queue -> viewModel.shuffleAndPlay(queue, sourceContext = "RECENTLY_PLAYED") },
                                                onPlayAll = { queue -> viewModel.playAll(queue, sourceContext = "RECENTLY_PLAYED") },
                                                onClearHistory = { viewModel.clearRecentlyPlayed() },
                                                onBackClick = { if (!viewModel.navigateBackTab()) viewModel.selectedTab.value = NavTab.HOME },
                                                onPlayNext = { viewModel.playNext(it) },
                                                onAddToQueue = { viewModel.addToQueue(it) },
                                                onAddToPlaylist = { songForAddToPlaylist = it },
                                                onDeleteSong = { song -> executeDeleteSong(song) },
                                                onEditTags = { songForTagEditing = it },
                                                dailyStats = dailyStats7Days,
                                                onOpenStatsDetail = { viewModel.navigateToTab(NavTab.STATS_DETAIL) }
                                            )
                                            NavTab.STATS_DETAIL -> ListeningStatsDetailScreen(
                                                playbackState = playbackState,
                                                getDailyStats = { viewModel.getDailyListeningStats(it) },
                                                getTimeOfDayStats = { viewModel.getTimeOfDayDistribution(it) },
                                                getDurationPlayedBetween = { start, end -> viewModel.getTotalDurationPlayedBetween(start, end) },
                                                getTrackCountBetween = { start, end -> viewModel.getTrackCountBetween(start, end) },
                                                getUniqueTrackCountBetween = { start, end -> viewModel.getUniqueTrackCountBetween(start, end) },
                                                getMostPlayedSongsSince = { start, limit -> viewModel.getMostPlayedSongsBetween(start, limit = limit) },
                                                getTopArtistsSince = { start, limit -> viewModel.getTopArtistsBetween(start, limit = limit) },
                                                getTopAlbumsSince = { start, limit -> viewModel.getTopAlbumsBetween(start, limit = limit) },
                                                onSongClick = { viewModel.playSong(it) },
                                                onPlayQueue = { song, queue -> viewModel.playSong(song, queue, sourceContext = "STATS_DETAIL") },
                                                onBackClick = { if (!viewModel.navigateBackTab()) viewModel.selectedTab.value = NavTab.HOME },
                                                onOpenRecentlyPlayed = { viewModel.navigateToTab(NavTab.RECENTLY_PLAYED) },
                                                loadWrappedData = { start, end -> viewModel.getWrappedDataForPeriod(start, end) }
                                            )
                                            NavTab.JAM -> JamScreen(
                                                controller = viewModel.jamController,
                                                songs = allSongs,
                                                onRequireAuthentication = { viewModel.openAuthScreen() }
                                            )
                                             NavTab.SETTINGS -> {
                                                 com.example.ui.screens.SettingsTabRoute(
                                                     viewModel = viewModel,
                                                     allFolders = allFolders,
                                                     sleepTimerState = sleepTimerState,
                                                     scanProgress = scanProgress,
                                                     userAccount = userAccount,
                                                     effectiveDisplayName = effectiveDisplayName,
                                                     customAvatarUri = customAvatarUri,
                                                     syncStatus = syncStatus,
                                                     uiTheme = uiTheme,
                                                     homeScreenLayout = homeScreenLayout,
                                                     nowPlayingLayout = nowPlayingLayout,
                                                     playlistsLayout = playlistsLayout,
                                                     isDynamicArtworkTheme = isDynamicArtworkTheme,
                                                     customAccentColor = customAccentColor,
                                                     accentColorPreset = accentColorPreset,
                                                     recentSearches = recentSearches,
                                                     homeSections = homeSections
                                                 )
                                             }
                                        }
                                    }
                                    }
                                }
                            }

                            // Full Screen Now Playing Sheet Overlay
                            AnimatedVisibility(
                                visible = showNowPlaying,
                                enter = slideInVertically { it },
                                exit = slideOutVertically { it }
                            ) {
                                val waveformStyle by viewModel.waveformStyle.collectAsStateWithLifecycle()
                                val availableAudioDevices by viewModel.availableAudioDevices.collectAsStateWithLifecycle()
                                val keepScreenAwakeSetting by viewModel.keepScreenAwake.collectAsStateWithLifecycle()
                                val gesturePlaybackEnabled by viewModel.gesturePlaybackEnabled.collectAsStateWithLifecycle()
                                val lyricsAutoScroll by viewModel.lyricsAutoScroll.collectAsStateWithLifecycle()
                                val isTranscribeTranslateEnabled by viewModel.isTranscribeTranslateEnabled.collectAsStateWithLifecycle()
                                val audioTranscribeMode by viewModel.audioTranscribeMode.collectAsStateWithLifecycle()
                                val audioTranscribeTargetLang by viewModel.audioTranscribeTargetLang.collectAsStateWithLifecycle()
                                val isTranscribingLyrics by viewModel.isTranscribingLyrics.collectAsStateWithLifecycle()
                                val transcribeStatusMessage by viewModel.transcribeStatusMessage.collectAsStateWithLifecycle()
                                val transcribeErrorMessage by viewModel.transcribeErrorMessage.collectAsStateWithLifecycle()
                                val canUndoLyrics by viewModel.canUndoLyricsForCurrentSong.collectAsStateWithLifecycle()
                                NowPlayingScreen(
                                    playbackState = playbackState,
                                    currentLyrics = currentLyrics,
                                    audioRouteInfo = currentAudioRoute,
                                    availableAudioDevices = availableAudioDevices,
                                    keepScreenAwake = keepScreenAwakeSetting,
                                    gesturePlaybackEnabled = gesturePlaybackEnabled,
                                    lyricsAutoScroll = lyricsAutoScroll,
                                    onSaveLyricsOffset = { songId, offsetMs ->
                                        viewModel.setLyricsSyncOffset(songId, offsetMs)
                                    },
                                    onGetLyricsOffset = { songId ->
                                        viewModel.getLyricsSyncOffset(songId)
                                    },
                                    onSelectAudioDevice = { viewModel.selectAudioRoute(it) },
                                    onLaunchSystemOutputSwitcher = { viewModel.launchSystemOutputSwitcher(this@MainActivity) },
                                    sleepTimerState = sleepTimerState,
                                    dynamicArtworkColor = dynamicArtworkColor,
                                    waveformStyle = waveformStyle,
                                    nowPlayingLayout = nowPlayingLayout,
                                    playbackPositionFlow = viewModel.playbackPositionMs,
                                    onDismiss = { viewModel.showNowPlaying.value = false },
                                    onTogglePlayPause = { viewModel.togglePlayPause() },
                                    onSkipNext = { viewModel.skipToNext() },
                                    onSkipPrevious = { viewModel.skipToPrevious() },
                                    onSeekTo = { viewModel.seekTo(it) },
                                    onFavoriteToggle = {
                                        playbackState.currentSong?.let { viewModel.toggleFavorite(it) }
                                    },
                                    onToggleShuffle = { viewModel.toggleShuffle() },
                                    onToggleRepeat = { viewModel.toggleRepeat() },
                                    onStartSleepTimer = { mins, finishLastSong -> viewModel.startSleepTimerMinutes(mins, finishLastSong) },
                                    onStartSongCountTimer = { songs -> viewModel.startSleepTimerSongs(songs) },
                                    onAddSleepTimerMinutes = { viewModel.addSleepTimerMinutes(it) },
                                    onCancelSleepTimer = { viewModel.cancelSleepTimer() },
                                    onCustomLrcSelected = { lrcText ->
                                        playbackState.currentSong?.let { viewModel.associateCustomLrc(it, lrcText) }
                                    },
                                    onReorderQueue = { from, to -> viewModel.reorderQueue(from, to) },
                                    onUpdateQueue = { viewModel.updateQueueOrder(it) },
                                    onRemoveFromQueue = { index -> viewModel.removeFromQueue(index) },
                                    onRemoveDuplicatesFromQueue = {
                                        val removed = viewModel.removeDuplicateSongsFromQueue()
                                        if (removed > 0) {
                                            android.widget.Toast.makeText(this@MainActivity, "Removed $removed duplicate song${if (removed > 1) "s" else ""} from queue", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(this@MainActivity, "No duplicate songs in queue", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onJumpToQueueItem = { index ->
                                        viewModel.jumpToQueueIndex(index)
                                    },
                                    onPlayNext = { viewModel.playNext(it) },
                                    onAddToPlaylist = { songForAddToPlaylist = it },
                                    onGetPlaylistsContainingSong = { viewModel.getPlaylistsContainingSong(it) },
                                    onRemoveFromPlaylist = { playlistId, songId -> viewModel.removeSongFromPlaylist(playlistId, songId) },
                                    onDeleteSong = { song -> executeDeleteSong(song) },
                                    onEditTags = { songForTagEditing = it },
                                    onForceFetchLyrics = { viewModel.forceFetchLyrics(it) },
                                    onSearchLyricsCandidates = { query, force -> viewModel.searchLyricsCandidates(query, force) },
                                    lyricsSearchResults = viewModel.lyricsSearchResults.collectAsState().value,
                                    isSearchingLyrics = viewModel.isSearchingLyrics.collectAsState().value,
                                    onApplyLyricsCandidate = { viewModel.applySelectedLyrics(it) },
                                    isTranscribeTranslateEnabled = isTranscribeTranslateEnabled,
                                    audioTranscribeMode = audioTranscribeMode,
                                    audioTranscribeTargetLang = audioTranscribeTargetLang,
                                    isTranscribingLyrics = isTranscribingLyrics,
                                    transcribeStatusMessage = transcribeStatusMessage,
                                    transcribeErrorMessage = transcribeErrorMessage,
                                    canUndoLyrics = canUndoLyrics,
                                    transcribeLyricsPreview = viewModel.transcribeLyricsPreview.collectAsState().value,
                                    onSaveTranscribedLyrics = { preview -> viewModel.saveTranscribedLyrics(preview) },
                                    onDiscardTranscribedLyrics = { viewModel.discardTranscribedLyrics() },
                                    onRetranscribedLyrics = { preview -> viewModel.retranscribeLyrics(preview) },
                                    onTranslateLyrics = { song, lang -> viewModel.translateLyricsForSong(song, lang) },
                                    onTranscribeAudio = { song, lang -> viewModel.transcribeAudioForSong(song, lang) },
                                    onUndoLyricsChanges = { songId -> viewModel.undoLyricsChanges(songId) },
                                    onClearTranscribeError = { viewModel.clearTranscribeError() }
                                )
                            }

                            songForAddToPlaylist?.let { targetSong ->
                                com.example.ui.components.AddToPlaylistDialog(
                                    song = targetSong,
                                    playlists = allPlaylists,
                                    onSelectPlaylist = { targetPlaylist ->
                                        viewModel.addSongToPlaylist(targetPlaylist.id, targetSong.id) { added ->
                                            val msg = if (added) "Added to playlist" else "Already in playlist"
                                            android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        songForAddToPlaylist = null
                                    },
                                    onCreateNewPlaylist = { newName ->
                                        viewModel.createPlaylist(newName)
                                    },
                                    onDismiss = { songForAddToPlaylist = null }
                                )
                            }

                            songForTagEditing?.let { targetSong ->
                                com.example.ui.components.TagEditorDialog(
                                    song = targetSong,
                                    initialLyrics = tagEditingLyricsText,
                                    onDismiss = { 
                                        songForTagEditing = null
                                        tagEditingLyricsText = ""
                                    },
                                    onSaveTags = { title, artist, album, genre, year, trackNumber, artworkUri, lyrics ->
                                        val performSave = {
                                            viewModel.updateSongTags(
                                                song = targetSong,
                                                newTitle = title,
                                                newArtist = artist,
                                                newAlbum = album,
                                                newGenre = genre,
                                                newYear = year,
                                                newTrackNumber = trackNumber,
                                                newArtworkUri = artworkUri,
                                                newLyrics = lyrics
                                            ) {
                                                songForTagEditing = null
                                                tagEditingLyricsText = ""
                                                android.widget.Toast.makeText(this@MainActivity, "Tags & lyrics updated on device and app", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }

                                        if (!com.example.util.StoragePermissionHelper.hasStoragePermission(this@MainActivity)) {
                                            storagePermissionDialogTitle = "Storage Permission Required"
                                            storagePermissionDialogText = "OffGrid Music requires storage permission to write audio ID3 tags and lyrics directly into the audio file on your device.\n\nPlease grant permission to ensure tags take effect across your local storage."
                                            pendingStorageAction = {
                                                performSave()
                                            }
                                            showStoragePermissionDialog = true
                                        } else {
                                            performSave()
                                        }
                                    }
                                )
                            }

                            if (showStoragePermissionDialog) {
                                AlertDialog(
                                    onDismissRequest = { showStoragePermissionDialog = false },
                                    title = {
                                        Text(
                                            text = storagePermissionDialogTitle,
                                            color = BentoTextPrimary,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = storagePermissionDialogText,
                                            color = BentoTextSecondary,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp
                                        )
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                showStoragePermissionDialog = false
                                                val action = pendingStorageAction
                                                com.example.util.StoragePermissionHelper.requestStoragePermission(this@MainActivity)
                                                action?.invoke()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                                        ) {
                                            Text("Grant Permission", color = BentoOnPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(
                                            onClick = {
                                                showStoragePermissionDialog = false
                                                pendingStorageAction?.invoke()
                                            }
                                        ) {
                                            Text("Continue Without Full Access", color = BentoTextSecondary)
                                        }
                                    },
                                    containerColor = BentoCardSurface,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                                )
                            }

                            val activeUpdate = (updateCheckResult as? UpdateCheckResult.UpdateAvailable)?.takeIf { !it.isDismissed }
                            val isEligibleForUpdateDialog = forceShowUpdateDialog ||
                                (selectedTab == NavTab.HOME && !showNowPlaying && !showAuthScreen && !showAccountScreen)
                            if (activeUpdate != null && !showSplash && isOnboardingCompleted && isEligibleForUpdateDialog) {
                                AppUpdateDialog(
                                    release = activeUpdate.release,
                                    onDismiss = {
                                        try {
                                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                            nm.cancel(com.example.service.notification.OffGridFirebaseMessagingService.NOTIFICATION_ID)
                                        } catch (_: Exception) {}
                                        viewModel.dismissUpdate(activeUpdate.release.tag)
                                    },
                                    onUpdateNow = { targetUrl ->
                                        viewModel.forceShowUpdateDialog.value = false
                                        try {
                                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                            nm.cancel(com.example.service.notification.OffGridFirebaseMessagingService.NOTIFICATION_ID)
                                        } catch (_: Exception) {}
                                        try {
                                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                            startActivity(browserIntent)
                                        } catch (_: Exception) {
                                            android.widget.Toast.makeText(this@MainActivity, "Could not open browser", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetLaunchIntent(intent)
        handleJamInviteIntent(intent)
        handleJamRecommendationIntent(intent)
    }

    private fun handleJamInviteIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val configuredHost = com.example.BuildConfig.JAM_INVITE_HOST.trim()
        val host = uri.host?.trim().orEmpty()
        val validHost = (configuredHost.isNotBlank() && host.equals(configuredHost, ignoreCase = true)) ||
            host.equals("offgrid-music.web.app", ignoreCase = true)
        if (!uri.scheme.equals("https", ignoreCase = true) || !validHost) return
        val segments = uri.pathSegments
        if (segments.size >= 2 && segments[0].equals("jam", ignoreCase = true)) {
            val code = com.example.jam.extractJamInviteCode(segments[1])
            if (code.length >= 12) pendingJamInviteCode.value = code
        }
    }

    private fun isJamLinkHandlingEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val host = BuildConfig.JAM_INVITE_HOST.trim()
        if (host.isEmpty()) return true

        return runCatching {
            val manager = getSystemService(DomainVerificationManager::class.java) ?: return@runCatching false
            val state = manager.getDomainVerificationUserState(packageName) ?: return@runCatching false
            val hostState = state.hostToStateMap[host]
            state.isLinkHandlingAllowed && (
                hostState == DomainVerificationUserState.DOMAIN_STATE_VERIFIED ||
                    hostState == DomainVerificationUserState.DOMAIN_STATE_SELECTED
                )
        }.getOrDefault(false)
    }

    private fun openJamLinkSettings() {
        val appSettingsUri = Uri.fromParts("package", packageName, null)
        val openByDefaultIntent = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
            data = appSettingsUri
        }
        try {
            startActivity(openByDefaultIntent)
        } catch (_: Exception) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = appSettingsUri
                })
            }
        }
    }

    private fun handleJamRecommendationIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val kind = extras.getString("kind").orEmpty()
        if (!kind.startsWith("jam_", ignoreCase = true)) return
        val recipientUid = extras.getString("recipientUid") ?: return
        val signedInUid = com.example.util.FirebaseInitializer.getAuth(this)?.currentUser?.uid
        if (signedInUid != null && recipientUid != signedInUid) return
        val sessionId = extras.getString("sessionId") ?: extras.getString("session_id")
        val recommendationId = extras.getString("recommendationId") ?: extras.getString("recommendation_id")
        if (!sessionId.isNullOrBlank() && !recommendationId.isNullOrBlank()) {
            pendingJamRecommendation.value = sessionId to recommendationId
        }
    }

    private fun handleWidgetLaunchIntent(intent: Intent?) {
        if (intent == null) return
        val widgetAction = intent.getStringExtra(com.example.widget.WidgetUpdateManager.EXTRA_WIDGET_ACTION)
            ?: intent.action?.takeIf { it.startsWith("com.example.widget.ACTION_") }
        if (!widgetAction.isNullOrBlank()) {
            intent.removeExtra(com.example.widget.WidgetUpdateManager.EXTRA_WIDGET_ACTION)
            if (intent.action?.startsWith("com.example.widget.ACTION_") == true) {
                intent.action = null
            }
            pendingWidgetActionRequest.value = widgetAction
            try {
                val vm = androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java]
                vm.dismissSplash()
                vm.enqueueOrExecuteWidgetAction(widgetAction)
            } catch (_: Exception) {}
        }
        if (intent.getBooleanExtra(com.example.widget.WidgetUpdateManager.EXTRA_OPEN_NOW_PLAYING, false)) {
            intent.removeExtra(com.example.widget.WidgetUpdateManager.EXTRA_OPEN_NOW_PLAYING)
            openNowPlayingRequest.value = System.currentTimeMillis()
            try {
                val vm = androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java]
                vm.dismissSplash()
                vm.showNowPlaying.value = true
            } catch (_: Exception) {}
        }

        val hasExplicitUpdateFlag =
            intent.getBooleanExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_OPEN_UPDATE_DIALOG, false) ||
            intent.getBooleanExtra("EXTRA_OPEN_UPDATE_DIALOG", false) ||
            intent.getBooleanExtra("open_update_dialog", false)

        val fcmReleaseTag = intent.getStringExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_RELEASE_TAG)
            ?: intent.getStringExtra("release_tag")
            ?: intent.getStringExtra("version")
            ?: ""

        val fcmReleaseUrl = intent.getStringExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_RELEASE_URL)
            ?: intent.getStringExtra("release_url")
            ?: intent.getStringExtra("url")
            ?: ""

        val fcmTitle = intent.getStringExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_RELEASE_TITLE)
            ?: intent.getStringExtra("title")
            ?: ""

        val fcmNotes = intent.getStringExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_RELEASE_NOTES)
            ?: intent.getStringExtra("body")
            ?: intent.getStringExtra("release_notes")
            ?: ""

        val fcmApkUrl = intent.getStringExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_APK_URL)
            ?: intent.getStringExtra("apk_url")

        if (hasExplicitUpdateFlag || fcmReleaseTag.isNotBlank() || fcmReleaseUrl.isNotBlank()) {
            intent.removeExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_OPEN_UPDATE_DIALOG)
            intent.removeExtra("EXTRA_OPEN_UPDATE_DIALOG")
            intent.removeExtra("open_update_dialog")
            intent.removeExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_RELEASE_TAG)
            intent.removeExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_RELEASE_URL)
            intent.removeExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_RELEASE_TITLE)
            intent.removeExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_RELEASE_NOTES)
            intent.removeExtra(com.example.service.notification.OffGridFirebaseMessagingService.EXTRA_UPDATE_APK_URL)
            intent.removeExtra("release_tag")
            intent.removeExtra("version")
            intent.removeExtra("release_url")
            intent.removeExtra("url")
            intent.removeExtra("title")
            intent.removeExtra("body")
            intent.removeExtra("release_notes")
            intent.removeExtra("apk_url")

            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(com.example.service.notification.OffGridFirebaseMessagingService.NOTIFICATION_ID)
            } catch (_: Exception) {}

            try {
                val vm = androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java]
                vm.dismissSplash()
                if (fcmReleaseTag.isNotBlank() || fcmReleaseUrl.isNotBlank()) {
                    vm.onUpdateNotificationTapped(
                        releaseTag = fcmReleaseTag,
                        title = fcmTitle,
                        notes = fcmNotes,
                        releaseUrl = fcmReleaseUrl,
                        apkUrl = fcmApkUrl
                    )
                } else {
                    vm.checkForUpdates(isManual = true)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityAlive = true
        try {
            com.example.service.PlaybackManager.activeInstance?.let { pm ->
                pm.onAppForegroundStateChanged(true)
                com.example.widget.WidgetUpdateManager.updateAllWidgets(applicationContext, pm.playbackState.value)
            }
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        isActivityAlive = true
        try {
            com.example.service.PlaybackManager.activeInstance?.onAppForegroundStateChanged(true)
        } catch (_: Throwable) {}
        // No heavy scanning in onResume to prevent UI stutter and resource starvation
    }

    private var lastScanTriggerTime = 0L

    private fun triggerQuickScan(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastScanTriggerTime < 60000L)) {
            return
        }
        if (com.example.util.StoragePermissionHelper.hasAudioReadPermission(this) || com.example.util.StoragePermissionHelper.hasStoragePermission(this)) {
            lastScanTriggerTime = now
            try {
                val viewModel = androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java]
                if (viewModel.isOnboardingCompleted.value && !viewModel.showSplash.value) {
                    viewModel.quickScanAndCleanLibrary()
                }
            } catch (_: Exception) {}
        }
    }

    private fun isExternalStorageManagerSafe(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
    }

    private fun hasAllFilesPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED ||
                        isExternalStorageManagerSafe()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                        isExternalStorageManagerSafe()
            }
            else -> {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun requestAllFilesPermission() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            try {
                requestPermissionLauncher.launch(needed.toTypedArray())
            } catch (_: Exception) {}
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!isExternalStorageManagerSafe()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        } else {
            checkPermissions()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            try {
                requestPermissionLauncher.launch(needed.toTypedArray())
            } catch (_: Exception) {}
        }
    }

    private fun registerMediaStoreObserver() {
        if (mediaStoreObserver != null) return
        if (!com.example.util.StoragePermissionHelper.hasAudioReadPermission(this) &&
            !com.example.util.StoragePermissionHelper.hasStoragePermission(this)
        ) {
            return
        }
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            private var lastScanTime = 0L
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val now = System.currentTimeMillis()
                if (now - lastScanTime > 60000L) {
                    lastScanTime = now
                    triggerQuickScan(force = false)
                }
            }
        }
        try {
            contentResolver.registerContentObserver(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            mediaStoreObserver = observer
        } catch (_: Exception) {}
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            isActivityAlive = false
            try {
                com.example.service.PlaybackManager.activeInstance?.onAppForegroundStateChanged(false)
            } catch (_: Throwable) {}
        }
        super.onStop()
        try {
            com.example.service.PlaybackManager.activeInstance?.ensureBackgroundNotification()
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            isActivityAlive = false
            try {
                com.example.service.PlaybackManager.activeInstance?.onAppForegroundStateChanged(false)
            } catch (_: Throwable) {}
            if (isFinishing) {
                try {
                    com.example.jam.JamFeatureController.activeInstance?.onAppClosed()
                } catch (_: Throwable) {}
            }
        }
        try {
            val pmState = com.example.service.PlaybackManager.activeInstance?.playbackState?.value
                ?: com.example.service.PlaybackState()
            com.example.widget.WidgetUpdateManager.updateAllWidgets(applicationContext, pmState)
        } catch (_: Throwable) {}
        super.onDestroy()
        mediaStoreObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
        }
        mediaStoreObserver = null
        if (currentActivity === this) currentActivity = null
    }

    private fun applyHighRefreshRate(enabled: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val win = window
                val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { display } catch (_: Throwable) { null }
                } else {
                    @Suppress("DEPRECATION")
                    try { win.windowManager.defaultDisplay } catch (_: Throwable) { null }
                }

                val modes = disp?.supportedModes
                if (!modes.isNullOrEmpty()) {
                    val curMode = disp.mode
                    val sameResModes = modes.filter {
                        it.physicalWidth == curMode.physicalWidth &&
                            it.physicalHeight == curMode.physicalHeight
                    }.ifEmpty { modes.toList() }

                    val maxMode = sameResModes.maxByOrNull { it.refreshRate }
                    val standard60HzMode = sameResModes.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
                    val targetModeId = if (enabled && maxMode != null) maxMode.modeId else (standard60HzMode?.modeId ?: 0)
                    val targetRefreshRate = if (enabled && maxMode != null) maxMode.refreshRate else (standard60HzMode?.refreshRate ?: 60f)

                    val params = win.attributes
                    if (params.preferredDisplayModeId != targetModeId || params.preferredRefreshRate != targetRefreshRate) {
                        params.preferredDisplayModeId = targetModeId
                        @Suppress("DEPRECATION")
                        params.preferredRefreshRate = targetRefreshRate
                        win.attributes = params
                    }
                }
            } catch (_: Throwable) {
                // Fallback gracefully if display mode setting fails on device
            }
        }
    }
}
