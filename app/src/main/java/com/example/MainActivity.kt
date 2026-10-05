package com.example

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.Song
import com.example.ui.components.FloatingPillNavBar
import com.example.ui.components.MiniPlayer
import com.example.ui.components.NavTab
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.MusicViewModel

class MainActivity : ComponentActivity() {

    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            try {
                val viewModel = androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java]
                viewModel.scanLibrary()
            } catch (_: Exception) {}
        }
    }

    private var mediaStoreObserver: android.database.ContentObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkPermissions()
        triggerQuickScan()
        registerMediaStoreObserver()

        setContent {
            val viewModel: MusicViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val uiTheme by viewModel.uiTheme.collectAsState()
            val accentColorPreset by viewModel.accentColorPreset.collectAsState()
            val customAccentColor by viewModel.customAccentColor.collectAsState()
            val isDynamicArtworkTheme by viewModel.isDynamicArtworkTheme.collectAsState()
            val dynamicArtworkColor by viewModel.dynamicArtworkColor.collectAsState()

            val effectiveAccentColor: Color? = when {
                isDynamicArtworkTheme && dynamicArtworkColor != null -> dynamicArtworkColor
                customAccentColor != 0 -> Color(customAccentColor)
                else -> null
            }

            MyApplicationTheme(
                uiTheme = uiTheme,
                themeMode = themeMode,
                accentColorPreset = accentColorPreset,
                accentColor = effectiveAccentColor
            ) {

                val selectedTab by viewModel.selectedTab.collectAsState()
                val showNowPlaying by viewModel.showNowPlaying.collectAsState()
                val searchTabQuery by viewModel.searchTabQuery.collectAsState()
                val librarySearchQuery by viewModel.librarySearchQuery.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()

                val allSongs by viewModel.allSongs.collectAsState()
                val favoriteSongs by viewModel.favoriteSongs.collectAsState()
                val recentlyPlayed by viewModel.recentlyPlayedSongs.collectAsState()
                val recentlyAdded by viewModel.recentlyAddedSongs.collectAsState()
                val allFolders by viewModel.allFolders.collectAsState()
                val allPlaylists by viewModel.allPlaylists.collectAsState()
                val mostPlayedPlaylists by viewModel.mostPlayedPlaylists.collectAsState()
                val scanProgress by viewModel.scanProgress.collectAsState()
                val playbackState by viewModel.playbackState.collectAsState()
                val sleepTimerState by viewModel.sleepTimerState.collectAsState()
                val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
                val currentLyrics by viewModel.currentLyrics.collectAsState()
                val currentAudioRoute by viewModel.currentAudioRoute.collectAsState()
                val librarySortOption by viewModel.librarySortOption.collectAsState()
                val librarySortAscending by viewModel.librarySortAscending.collectAsState()
                val recentSearches by viewModel.recentSearches.collectAsState()
                val homeScreenLayout by viewModel.homeScreenLayout.collectAsState()
                val speedDialSongs by viewModel.speedDialSongs.collectAsState()
                val homeSections by viewModel.homeSections.collectAsState()
                val nowPlayingLayout by viewModel.nowPlayingLayout.collectAsState()
                val playlistsLayout by viewModel.playlistsLayout.collectAsState()

                val userAccount by viewModel.userAccount.collectAsState()
                val effectiveDisplayName by viewModel.effectiveDisplayName.collectAsState()
                val customAvatarUri by viewModel.customAvatarUri.collectAsState()
                val customDisplayName by viewModel.customDisplayName.collectAsState()
                val showAuthScreen by viewModel.showAuthScreen.collectAsState()
                val showAccountScreen by viewModel.showAccountScreen.collectAsState()
                val authUiState by viewModel.authUiState.collectAsState()
                val syncStatus by viewModel.syncStatus.collectAsState()

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
                androidx.activity.compose.BackHandler(enabled = true) {
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
                    val listener = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            hasStorageAccess = hasAllFilesPermission()
                        }
                    }
                    val lifecycle = this@MainActivity.lifecycle
                    lifecycle.addObserver(listener)
                    onDispose {
                        lifecycle.removeObserver(listener)
                    }
                }

                if (!isOnboardingCompleted) {
                    OnboardingScreen(
                        scanProgress = scanProgress,
                        hasAllFilesAccess = hasStorageAccess,
                        onRequestStoragePermission = { requestAllFilesPermission() },
                        onStartScan = { viewModel.scanLibrary() },
                        onComplete = { viewModel.completeOnboarding() }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BentoBackground)
                    ) {
                        val highRefreshRateSetting by viewModel.highRefreshRate.collectAsState()
                        LaunchedEffect(highRefreshRateSetting) {
                            applyHighRefreshRate(highRefreshRateSetting)
                        }

                        val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
                        val selectedSmartPlaylist by viewModel.selectedSmartPlaylist.collectAsState()
                        val smartPlaylists by viewModel.smartPlaylists.collectAsState()
                        val showSplash by viewModel.showSplash.collectAsState()
                        val selectedPlaylistSongs by remember(selectedPlaylist) {
                            val cur = selectedPlaylist
                            if (cur != null) {
                                viewModel.getPlaylistSongs(cur.id)
                            } else {
                                kotlinx.coroutines.flow.flowOf(emptyList())
                            }
                        }.collectAsState(initial = emptyList())
                        val dailyStats7Days by remember { viewModel.getDailyListeningStats(7) }.collectAsState(initial = emptyList())

                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = BentoBackground,
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
                                        val shouldShowMiniPlayer = playbackState.currentSong != null &&
                                            !showAccountScreen &&
                                            !showAuthScreen &&
                                            selectedTab != NavTab.SETTINGS

                                        if (shouldShowMiniPlayer) {
                                            val miniPlayerShowPrevious by viewModel.miniPlayerShowPrevious.collectAsState()
                                            val miniPlayerShowNext by viewModel.miniPlayerShowNext.collectAsState()
                                            val miniPlayerShowFavorite by viewModel.miniPlayerShowFavorite.collectAsState()
                                            val miniPlayerStyle by viewModel.miniPlayerStyle.collectAsState()

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
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                val currentPlaylist = selectedPlaylist
                                val currentSmartPlaylist = selectedSmartPlaylist
                                if (currentPlaylist != null) {
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
                                            onSignOut = { viewModel.signOut() }
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
                                            onShuffleAll = { queue -> viewModel.shuffleAndPlay(queue, sourceContext = "LIBRARY") },
                                            recentSearches = recentSearches,
                                            onAddRecentSearch = { viewModel.addRecentSearch(it) },
                                            onDeleteRecentSearch = { viewModel.deleteRecentSearch(it) },
                                            onClearRecentSearches = { viewModel.clearRecentSearches() },
                                            scanProgress = scanProgress
                                        )
                                        NavTab.PLAYLISTS -> {
                                            val playlistViewMode by viewModel.playlistViewMode.collectAsState()
                                            PlaylistsScreen(
                                                playlists = allPlaylists,
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
                                            onSongClick = { viewModel.playSong(it, recentlyPlayed, sourceContext = "RECENTLY_PLAYED") },
                                            onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                            onShuffleAll = {
                                                if (recentlyPlayed.isNotEmpty()) {
                                                    viewModel.shuffleAndPlay(recentlyPlayed, sourceContext = "RECENTLY_PLAYED")
                                                }
                                            },
                                            onPlayAll = {
                                                if (recentlyPlayed.isNotEmpty()) {
                                                    viewModel.playAll(recentlyPlayed, sourceContext = "RECENTLY_PLAYED")
                                                }
                                            },
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
                                }

                                if (showAccountScreen) {
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
                                        onUpdateDisplayName = { viewModel.updateUserDisplayName(it) },
                                        onUpdateAvatar = { viewModel.updateProfileAvatar(it) },
                                        onSignOut = { viewModel.signOut() },
                                        onBackClick = { viewModel.closeAccountScreen() }
                                    )
                                }
                            }
                        }

                        // Full Screen Now Playing Sheet Overlay
                        AnimatedVisibility(
                            visible = showNowPlaying,
                            enter = slideInVertically { it },
                            exit = slideOutVertically { it }
                        ) {
                            val waveformStyle by viewModel.waveformStyle.collectAsState()
                            val availableAudioDevices by viewModel.availableAudioDevices.collectAsState()
                            val keepScreenAwakeSetting by viewModel.keepScreenAwake.collectAsState()
                            val gesturePlaybackEnabled by viewModel.gesturePlaybackEnabled.collectAsState()
                            val lyricsAutoScroll by viewModel.lyricsAutoScroll.collectAsState()
                            val isTranscribeTranslateEnabled by viewModel.isTranscribeTranslateEnabled.collectAsState()
                            val audioTranscribeMode by viewModel.audioTranscribeMode.collectAsState()
                            val audioTranscribeTargetLang by viewModel.audioTranscribeTargetLang.collectAsState()
                            val isTranscribingLyrics by viewModel.isTranscribingLyrics.collectAsState()
                            val transcribeStatusMessage by viewModel.transcribeStatusMessage.collectAsState()
                            val transcribeErrorMessage by viewModel.transcribeErrorMessage.collectAsState()
                            val canUndoLyrics by viewModel.canUndoLyricsForCurrentSong.collectAsState()
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
                                onSaveTags = { title, artist, album, artworkUri, lyrics ->
                                    val performSave = {
                                        viewModel.updateSongTags(
                                            song = targetSong,
                                            newTitle = title,
                                            newArtist = artist,
                                            newAlbum = album,
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

                        // App Opening Splash Screen Overlay for OffGrid Music
                        AnimatedVisibility(
                            visible = showSplash,
                            enter = fadeIn(),
                            exit = fadeOut(tween(400))
                        ) {
                            com.example.ui.components.SplashScreen(
                                onDismiss = { viewModel.dismissSplash() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
                viewModel.quickScanAndCleanLibrary()
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
            requestPermissionLauncher.launch(needed.toTypedArray())
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
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun registerMediaStoreObserver() {
        if (mediaStoreObserver != null) return
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

    override fun onDestroy() {
        super.onDestroy()
        mediaStoreObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
        }
        mediaStoreObserver = null
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
                    val maxMode = modes.maxByOrNull { it.refreshRate }
                    val params = win.attributes
                    if (enabled && maxMode != null) {
                        params.preferredDisplayModeId = maxMode.modeId
                        @Suppress("DEPRECATION")
                        params.preferredRefreshRate = maxMode.refreshRate
                    } else {
                        // Explicitly switch to standard 60Hz mode to prevent sticking to 90Hz/120Hz
                        val standard60HzMode = modes.firstOrNull { it.refreshRate in 58f..62f }
                            ?: modes.minByOrNull { it.refreshRate }
                        if (standard60HzMode != null) {
                            params.preferredDisplayModeId = standard60HzMode.modeId
                            @Suppress("DEPRECATION")
                            params.preferredRefreshRate = standard60HzMode.refreshRate
                        } else {
                            params.preferredDisplayModeId = 0
                            @Suppress("DEPRECATION")
                            params.preferredRefreshRate = 60f
                        }
                    }
                    win.attributes = params
                }
            } catch (_: Throwable) {
                // Fallback gracefully if display mode setting fails on device
            }
        }
    }
}
