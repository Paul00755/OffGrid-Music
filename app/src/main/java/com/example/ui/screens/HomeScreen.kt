package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AlbumStat
import com.example.data.model.ArtistStat
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.scanner.ScanProgress
import com.example.service.PlaybackState
import com.example.service.SleepTimerState
import com.example.ui.components.AlbumArtView
import com.example.ui.components.BentoCard
import com.example.ui.components.SongItem
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Calendar

enum class StatsPeriod(val label: String, val days: Int) {
    TODAY("Today", 0),
    YESTERDAY("Yesterday", 1),
    WEEK("This Week", 7),
    MONTH("This Month", 30),
    ALL_TIME("All Time", 3650)
}

enum class MostPlayedFilter(val label: String, val days: Int) {
    THREE_DAYS("3 Days", 3),
    SEVEN_DAYS("7 Days", 7),
    THIRTY_DAYS("30 Days", 30),
    THIS_MONTH("This Month", 30),
    ALL_TIME("All Time", 3650)
}

@Composable
fun DottedDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outline,
    strokeWidth: Float = 3f,
    intervals: FloatArray = floatArrayOf(8f, 8f)
) {
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(1.dp)
    ) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width, 0f),
            strokeWidth = strokeWidth,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(intervals, 0f)
        )
    }
}

@Composable
fun SidebarMenuItem(
    icon: ImageVector,
    label: String,
    badge: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BentoPrimary.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = BentoPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = BentoTextPrimary
            )
        }
        if (badge != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BentoPrimary.copy(alpha = 0.10f),
                border = androidx.compose.foundation.BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.22f))
            ) {
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    allSongs: List<Song>,
    favoriteSongs: List<Song>,
    recentlyPlayed: List<Song>,
    recentlyAdded: List<Song>,
    allPlaylists: List<Playlist>,
    mostPlayedPlaylists: List<Playlist> = emptyList(),
    playbackState: PlaybackState,
    scanProgress: ScanProgress,
    sleepTimerState: SleepTimerState,
    onSongClick: (Song) -> Unit,
    onPlaySongQueue: (Song, List<Song>) -> Unit,
    onTogglePlayPause: () -> Unit,
    onQuickShuffle: () -> Unit,
    onScanClick: () -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: (String?) -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenRecentlyPlayed: () -> Unit = {},
    onOpenStatsDetail: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onSelectPlaylist: (Playlist) -> Unit = {},
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    // Stats provider callbacks
    getDurationPlayedBetween: (Long, Long) -> kotlinx.coroutines.flow.Flow<Long>,
    getTrackCountBetween: (Long, Long) -> kotlinx.coroutines.flow.Flow<Int>,
    getUniqueTrackCountBetween: (Long, Long) -> kotlinx.coroutines.flow.Flow<Int>,
    getMostPlayedSongsSince: (Long, Int) -> kotlinx.coroutines.flow.Flow<List<Song>>,
    getTopArtistsSince: (Long, Int) -> kotlinx.coroutines.flow.Flow<List<ArtistStat>>,
    getTopAlbumsSince: (Long, Int) -> kotlinx.coroutines.flow.Flow<List<AlbumStat>>,
    homeScreenLayout: String = "BENTO_DYNAMIC",
    speedDialSongs: List<Song> = emptyList(),
    onRefreshSpeedDial: () -> Unit = {},
    homeSections: List<String> = listOf("continue_listening", "quick_actions", "listening_stats", "most_played", "most_played_playlists", "recently_played", "recently_added", "top_artists", "top_albums"),
    playbackPositionFlow: kotlinx.coroutines.flow.StateFlow<Long>? = null,
    userAccount: com.example.auth.UserAccount? = null,
    effectiveDisplayName: String = "Music Lover",
    customAvatarUri: String? = null,
    onOpenAccount: () -> Unit = {},
    onOpenAuth: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onScreenEntered: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedMostPlayedFilter by remember { mutableStateOf(MostPlayedFilter.SEVEN_DAYS) }

    // Calculate start timestamp for selected filter using strict midnight boundaries
    val filterStartTimeMs = remember(selectedMostPlayedFilter) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (selectedMostPlayedFilter.days > 0) {
            cal.add(Calendar.DAY_OF_YEAR, -(selectedMostPlayedFilter.days - 1))
        }
        cal.timeInMillis
    }

    val mostPlayedFlow = remember(filterStartTimeMs) { getMostPlayedSongsSince(filterStartTimeMs, 10) }
    val topArtistsFlow = remember(filterStartTimeMs) { getTopArtistsSince(filterStartTimeMs, 8) }
    val topAlbumsFlow = remember(filterStartTimeMs) { getTopAlbumsSince(filterStartTimeMs, 8) }

    val mostPlayedFilteredSongs by mostPlayedFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val topArtists by topArtistsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val topAlbums by topAlbumsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // Current/Last Song for "Continue Listening"
    val continueSong = playbackState.currentSong ?: recentlyPlayed.firstOrNull() ?: allSongs.firstOrNull()

    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }

    val openSidebar: () -> Unit = {
        com.example.util.HapticUtils.performClickHaptic(context)
        coroutineScope.launch { drawerState.open() }
    }
    val closeSidebarAndRun: (() -> Unit) -> Unit = { action ->
        com.example.util.HapticUtils.performClickHaptic(context)
        coroutineScope.launch { drawerState.snapTo(DrawerValue.Closed) }
        action()
    }

    LaunchedEffect(Unit) {
        onScreenEntered()
    }

    // Pre-decode thumbnails for visible Home feed songs so scrolling has zero frame drops
    LaunchedEffect(speedDialSongs, recentlyPlayed, recentlyAdded, mostPlayedFilteredSongs) {
        val homeVisibleSongs = buildList {
            continueSong?.let { add(it) }
            addAll(speedDialSongs.take(12))
            addAll(mostPlayedFilteredSongs.take(10))
            addAll(recentlyPlayed.take(12))
            addAll(recentlyAdded.take(12))
        }.distinctBy { it.id }
        if (homeVisibleSongs.isNotEmpty()) {
            com.example.ui.components.ArtworkResolutionCache.preloadWindow(
                context = context,
                songs = homeVisibleSongs,
                firstVisibleIndex = 0,
                windowAhead = homeVisibleSongs.size,
                windowBehind = 0
            )
        }
    }

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 4..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..22 -> "Good Evening"
            else -> "Late Night Vibes"
        }
    }

    var isDrawerClosedAnchorReady by remember { mutableStateOf(false) }
    val isDrawerActiveOrAnchored = drawerState.isOpen ||
        drawerState.isAnimationRunning ||
        drawerState.targetValue == DrawerValue.Open ||
        (isDrawerClosedAnchorReady && !drawerState.currentOffset.isNaN())

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = if (isDrawerActiveOrAnchored) DrawerDefaults.scrimColor else Color.Transparent,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = BentoCardSurface,
                drawerContentColor = BentoTextPrimary,
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 320.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val offset = drawerState.currentOffset
                        if (!offset.isNaN() && offset < -1f) {
                            isDrawerClosedAnchorReady = true
                        }
                        val isClosedAtRest = drawerState.currentValue == DrawerValue.Closed &&
                            drawerState.targetValue == DrawerValue.Closed &&
                            !drawerState.isAnimationRunning
                        val shouldHide = if (isClosedAtRest) {
                            !isDrawerClosedAnchorReady ||
                                offset.isNaN() ||
                                offset >= -1f ||
                                (size.width > 0f && offset <= -(size.width - 2f))
                        } else {
                            false
                        }
                        if (shouldHide) {
                            alpha = 0f
                            translationX = -10000f
                        } else {
                            alpha = 1f
                            translationX = 0f
                        }
                    }
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
            ) {
                // Sidebar Header with Account Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BentoPrimary.copy(alpha = 0.08f))
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = BentoPrimary.copy(alpha = 0.15f),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "OFFGRID MUSIC",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Account Card inside Drawer
                    val isLoggedIn = userAccount != null && (!userAccount.email.isNullOrBlank() || !userAccount.displayName.isNullOrBlank()) && !userAccount.isAnonymous
                    val avatarUrl = customAvatarUri ?: (if (isLoggedIn) userAccount?.photoUrl else null)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                closeSidebarAndRun {
                                    if (isLoggedIn) onOpenAccount() else onOpenAuth()
                                }
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = BentoPrimary.copy(alpha = 0.18f),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    if (!avatarUrl.isNullOrBlank()) {
                                        coil.compose.AsyncImage(
                                            model = avatarUrl,
                                            contentDescription = "Profile Photo",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        )
                                    } else if (isLoggedIn && !userAccount?.displayName.isNullOrBlank()) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = userAccount!!.displayName!!.trim().first().uppercase(),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.sp,
                                                color = BentoPrimary
                                            )
                                        }
                                    } else if (isLoggedIn && !userAccount?.email.isNullOrBlank()) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = userAccount!!.email!!.trim().first().uppercase(),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.sp,
                                                color = BentoPrimary
                                            )
                                        }
                                    } else {
                                        // When not logged in using Google account or email:
                                        // Show normal portrait (AccountCircle, matching top nav bar) instead of displaying letter M
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.AccountCircle,
                                                contentDescription = "Profile Photo",
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isLoggedIn) effectiveDisplayName else "Welcome to OffGrid",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isLoggedIn) (userAccount?.email ?: "Account connected") else "Offline mode active",
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isLoggedIn) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = BentoPrimary.copy(alpha = 0.14f),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                closeSidebarAndRun { onOpenAccount() }
                                            }
                                    ) {
                                        Text(
                                            text = "Manage Account",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoPrimary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFFF5252).copy(alpha = 0.12f),
                                        modifier = Modifier
                                            .clickable {
                                                closeSidebarAndRun { onSignOut() }
                                            }
                                    ) {
                                        Text(
                                            text = "Sign Out",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFFF5252),
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = BentoPrimary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                closeSidebarAndRun { onOpenAuth() }
                                            }
                                    ) {
                                        Text(
                                            text = "Sign In / Register",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoOnPrimary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${allSongs.size} Songs • ${allPlaylists.size} Playlists",
                        fontSize = 11.5.sp,
                        color = BentoTextSubtle
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Sidebar Menu Items with Dotted Line Dividers
                SidebarMenuItem(
                    icon = Icons.Default.BarChart,
                    label = "Listening Stats & Insights",
                    badge = "Stats",
                    onClick = {
                        closeSidebarAndRun { onOpenStatsDetail() }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Search,
                    label = "Search Songs & Artists",
                    badge = null,
                    onClick = {
                        closeSidebarAndRun { onOpenSearch() }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Equalizer,
                    label = "Equalizer & Sound Effects",
                    badge = "Graph EQ",
                    onClick = {
                        closeSidebarAndRun { onOpenSettings("EQUALIZER") }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Storage,
                    label = "Offline Cache & Storage",
                    badge = null,
                    onClick = {
                        closeSidebarAndRun { onOpenSettings("CACHE") }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Palette,
                    label = "UI & Visual Appearance",
                    badge = null,
                    onClick = {
                        closeSidebarAndRun { onOpenSettings("UI") }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Settings,
                    label = "All Settings & Audio Preferences",
                    badge = null,
                    onClick = {
                        closeSidebarAndRun { onOpenSettings("ALL") }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Timer,
                    label = "Sleep Timer",
                    badge = if (sleepTimerState.isActive) {
                        if (sleepTimerState.mode == "SONGS") {
                            if (sleepTimerState.isFinalSleepTrack) "Final track"
                            else if (sleepTimerState.elapsedSongs > 0) "${sleepTimerState.elapsedSongs}/${sleepTimerState.totalSongs}"
                            else "${sleepTimerState.remainingSongs} songs"
                        } else "${sleepTimerState.remainingSeconds / 60}m"
                    } else "Off",
                    onClick = {
                        closeSidebarAndRun { onOpenSettings("SLEEP_TIMER") }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.QueueMusic,
                    label = "Playlists",
                    badge = "${allPlaylists.size}",
                    onClick = {
                        closeSidebarAndRun { onOpenPlaylists() }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.LibraryMusic,
                    label = "Music Library",
                    badge = "${allSongs.size}",
                    onClick = {
                        closeSidebarAndRun { onOpenLibrary() }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Favorite,
                    label = "Favorite Songs",
                    badge = "${favoriteSongs.size}",
                    onClick = {
                        closeSidebarAndRun { onOpenFavorites() }
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Refresh,
                    label = "Recently Played",
                    badge = if (recentlyPlayed.isNotEmpty()) "${recentlyPlayed.size}" else null,
                    onClick = {
                        closeSidebarAndRun { onOpenRecentlyPlayed() }
                    }
                )
                DottedDivider()
            }
        }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
        val currentLayout = com.example.ui.theme.HomeScreenLayout.fromId(homeScreenLayout)
        when (currentLayout) {
            com.example.ui.theme.HomeScreenLayout.HERO_SPOTLIGHT -> {
                com.example.ui.screens.layouts.HomeScreenHeroSpotlight(
                    allSongs = allSongs,
                    favoriteSongs = favoriteSongs,
                    recentlyPlayed = recentlyPlayed,
                    allPlaylists = allPlaylists,
                    playbackState = playbackState,
                    scanProgress = scanProgress,
                    greeting = greeting,
                    onSongClick = onSongClick,
                    onPlaySongQueue = onPlaySongQueue,
                    onTogglePlayPause = onTogglePlayPause,
                    onQuickShuffle = onQuickShuffle,
                    onFavoriteToggle = onFavoriteToggle,
                    onOpenNowPlaying = onOpenNowPlaying,
                    onOpenDrawer = openSidebar,
                    onOpenSearch = onOpenSearch,
                    onScanClick = onScanClick,
                    onOpenSettings = onOpenSettings,
                    onOpenFavorites = onOpenFavorites,
                    onOpenRecentlyPlayed = onOpenRecentlyPlayed,
                    onOpenStatsDetail = onOpenStatsDetail,
                    onOpenLibrary = onOpenLibrary,
                    onOpenPlaylists = onOpenPlaylists,
                    onSelectPlaylist = onSelectPlaylist,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist,
                    onDeleteSong = onDeleteSong,
                    onEditTags = onEditTags,
                    topArtists = topArtists,
                    topAlbums = topAlbums,
                    speedDialSongs = speedDialSongs,
                    onRefreshSpeedDial = onRefreshSpeedDial,
                    homeSections = homeSections,
                    playbackPositionFlow = playbackPositionFlow,
                    recentlyAdded = recentlyAdded,
                    mostPlayedPlaylists = mostPlayedPlaylists,
                    mostPlayedFilteredSongs = mostPlayedFilteredSongs,
                    selectedMostPlayedFilter = selectedMostPlayedFilter,
                    onSelectMostPlayedFilter = { selectedMostPlayedFilter = it },
                    getDurationPlayedBetween = getDurationPlayedBetween,
                    getTrackCountBetween = getTrackCountBetween,
                    getUniqueTrackCountBetween = getUniqueTrackCountBetween,
                    userAccount = userAccount,
                    effectiveDisplayName = effectiveDisplayName,
                    customAvatarUri = customAvatarUri,
                    onOpenAccount = onOpenAccount,
                    onOpenAuth = onOpenAuth,
                    modifier = modifier
                )
            }
            com.example.ui.theme.HomeScreenLayout.MINIMAL_FEED -> {
                com.example.ui.screens.layouts.HomeScreenMinimalFeed(
                    allSongs = allSongs,
                    favoriteSongs = favoriteSongs,
                    recentlyPlayed = recentlyPlayed,
                    allPlaylists = allPlaylists,
                    playbackState = playbackState,
                    greeting = greeting,
                    onSongClick = onSongClick,
                    onPlaySongQueue = onPlaySongQueue,
                    onTogglePlayPause = onTogglePlayPause,
                    onQuickShuffle = onQuickShuffle,
                    onOpenNowPlaying = onOpenNowPlaying,
                    onOpenDrawer = openSidebar,
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings,
                    onOpenLibrary = onOpenLibrary,
                    onOpenFavorites = onOpenFavorites,
                    onOpenPlaylists = onOpenPlaylists,
                    onFavoriteToggle = onFavoriteToggle,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist,
                    onDeleteSong = onDeleteSong,
                    onEditTags = onEditTags,
                    speedDialSongs = speedDialSongs,
                    onRefreshSpeedDial = onRefreshSpeedDial,
                    homeSections = homeSections,
                    playbackPositionFlow = playbackPositionFlow,
                    onOpenRecentlyPlayed = onOpenRecentlyPlayed,
                    onOpenStatsDetail = onOpenStatsDetail,
                    onSelectPlaylist = onSelectPlaylist,
                    topArtists = topArtists,
                    topAlbums = topAlbums,
                    recentlyAdded = recentlyAdded,
                    mostPlayedPlaylists = mostPlayedPlaylists,
                    mostPlayedFilteredSongs = mostPlayedFilteredSongs,
                    selectedMostPlayedFilter = selectedMostPlayedFilter,
                    onSelectMostPlayedFilter = { selectedMostPlayedFilter = it },
                    getDurationPlayedBetween = getDurationPlayedBetween,
                    getTrackCountBetween = getTrackCountBetween,
                    getUniqueTrackCountBetween = getUniqueTrackCountBetween,
                    userAccount = userAccount,
                    effectiveDisplayName = effectiveDisplayName,
                    customAvatarUri = customAvatarUri,
                    onOpenAccount = onOpenAccount,
                    onOpenAuth = onOpenAuth,
                    modifier = modifier
                )
            }
            com.example.ui.theme.HomeScreenLayout.STUDIO_DASHBOARD -> {
                com.example.ui.screens.layouts.HomeScreenStudioDashboard(
                    allSongs = allSongs,
                    favoriteSongs = favoriteSongs,
                    recentlyPlayed = recentlyPlayed,
                    allPlaylists = allPlaylists,
                    playbackState = playbackState,
                    greeting = greeting,
                    onSongClick = onSongClick,
                    onPlaySongQueue = onPlaySongQueue,
                    onTogglePlayPause = onTogglePlayPause,
                    onQuickShuffle = onQuickShuffle,
                    onOpenNowPlaying = onOpenNowPlaying,
                    onOpenDrawer = openSidebar,
                    onOpenSearch = onOpenSearch,
                    onScanClick = onScanClick,
                    onOpenSettings = onOpenSettings,
                    onOpenLibrary = onOpenLibrary,
                    onOpenFavorites = onOpenFavorites,
                    onOpenPlaylists = onOpenPlaylists,
                    onFavoriteToggle = onFavoriteToggle,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist,
                    onDeleteSong = onDeleteSong,
                    onEditTags = onEditTags,
                    speedDialSongs = speedDialSongs,
                    onRefreshSpeedDial = onRefreshSpeedDial,
                    homeSections = homeSections,
                    playbackPositionFlow = playbackPositionFlow,
                    onOpenRecentlyPlayed = onOpenRecentlyPlayed,
                    onOpenStatsDetail = onOpenStatsDetail,
                    onSelectPlaylist = onSelectPlaylist,
                    topArtists = topArtists,
                    topAlbums = topAlbums,
                    recentlyAdded = recentlyAdded,
                    mostPlayedPlaylists = mostPlayedPlaylists,
                    mostPlayedFilteredSongs = mostPlayedFilteredSongs,
                    selectedMostPlayedFilter = selectedMostPlayedFilter,
                    onSelectMostPlayedFilter = { selectedMostPlayedFilter = it },
                    getDurationPlayedBetween = getDurationPlayedBetween,
                    getTrackCountBetween = getTrackCountBetween,
                    getUniqueTrackCountBetween = getUniqueTrackCountBetween,
                    userAccount = userAccount,
                    effectiveDisplayName = effectiveDisplayName,
                    customAvatarUri = customAvatarUri,
                    onOpenAccount = onOpenAccount,
                    onOpenAuth = onOpenAuth,
                    modifier = modifier
                )
            }
            com.example.ui.theme.HomeScreenLayout.BENTO_DYNAMIC -> {
                val bentoListState = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(
                    state = bentoListState,
                    modifier = modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                // Header Row with Time-Based Greeting, Sidebar Drawer Toggle, and Top Nav Search Option
                item(key = "home_bento_header", contentType = "home_bento_header") {
                    val isTopLoggedIn = userAccount != null && (!userAccount.email.isNullOrBlank() || !userAccount.displayName.isNullOrBlank()) && !userAccount.isAnonymous
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = openSidebar,
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Menu,
                                        contentDescription = "Open Sidebar Menu",
                                        tint = BentoTextPrimary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = if (isTopLoggedIn) "Welcome, $effectiveDisplayName" else greeting,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isTopLoggedIn) (userAccount?.email ?: "OffGrid Music") else "OffGrid Music",
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // User Account Avatar Button
                            val topAvatar = customAvatarUri ?: (if (isTopLoggedIn) userAccount?.photoUrl else null)
                            Surface(
                                onClick = {
                                    if (isTopLoggedIn) onOpenAccount() else onOpenAuth()
                                },
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                                modifier = Modifier.size(36.dp)
                            ) {
                                if (!topAvatar.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = topAvatar,
                                        contentDescription = "User Account",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isTopLoggedIn) Icons.Default.Person else Icons.Default.AccountCircle,
                                            contentDescription = "User Account",
                                            tint = if (isTopLoggedIn) BentoPrimary else BentoTextPrimary,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }

                            Surface(
                                onClick = onOpenSearch,
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = BentoTextPrimary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }

                            Surface(
                                onClick = onOpenFavorites,
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "Favorites",
                                        tint = BentoAccentPinkText,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }

                            Surface(
                                onClick = onScanClick,
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Rescan Library",
                                        tint = BentoTextPrimary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }

                            Surface(
                                onClick = { onOpenSettings(null) },
                                shape = CircleShape,
                                color = BentoPrimary.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.32f)),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Settings",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                    }
                }

        // Scanning Banner if active
        if (scanProgress.isScanning || scanProgress.status == com.example.scanner.ScanStatus.SCANNING) {
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = BentoCardSurface
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = BentoPrimary
                                )
                                Text(
                                    text = "Scanning Music Library...",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (scanProgress.isScanning || scanProgress.status == com.example.scanner.ScanStatus.SCANNING) "${scanProgress.validTracksFound} tracks indexed" else "${scanProgress.validTracksFound} tracks found",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = BentoTextSecondary
                            )
                        }
                        Text(
                            text = if (scanProgress.currentFolder.isNotBlank()) "Scanning (${scanProgress.currentFolder})..." else "Scanning...",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary
                        )
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape),
                            color = BentoPrimary,
                            trackColor = BentoBorder.copy(alpha = 0.30f)
                        )
                    }
                }
            }
        }

        // Authoritative Dynamic Home Sections
        renderHomeFeedSections(
            homeSections = homeSections,
            speedDialSongs = speedDialSongs,
            continueSong = continueSong,
            playbackState = playbackState,
            playbackPositionFlow = playbackPositionFlow,
            allSongs = allSongs,
            favoriteSongs = favoriteSongs,
            recentlyPlayed = recentlyPlayed,
            recentlyAdded = recentlyAdded,
            topArtists = topArtists,
            topAlbums = topAlbums,
            allPlaylists = allPlaylists,
            mostPlayedPlaylists = mostPlayedPlaylists,
            mostPlayedFilteredSongs = mostPlayedFilteredSongs,
            selectedMostPlayedFilter = selectedMostPlayedFilter,
            onSelectMostPlayedFilter = { selectedMostPlayedFilter = it },
            onPlaySongQueue = onPlaySongQueue,
            onRefreshSpeedDial = onRefreshSpeedDial,
            onSongClick = onSongClick,
            onFavoriteToggle = onFavoriteToggle,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = onAddToPlaylist,
            onDeleteSong = onDeleteSong,
            onEditTags = onEditTags,
            onOpenNowPlaying = onOpenNowPlaying,
            onTogglePlayPause = onTogglePlayPause,
            onOpenRecentlyPlayed = onOpenRecentlyPlayed,
            onOpenStatsDetail = onOpenStatsDetail,
            onSelectPlaylist = onSelectPlaylist,
            onOpenFavorites = onOpenFavorites,
            onOpenLibrary = onOpenLibrary,
            onOpenPlaylists = onOpenPlaylists,
            onQuickShuffle = onQuickShuffle,
            getDurationPlayedBetween = getDurationPlayedBetween,
            getTrackCountBetween = getTrackCountBetween,
            getUniqueTrackCountBetween = getUniqueTrackCountBetween
        )

        // Empty state call to action when no music history is present
        if (allSongs.isEmpty() && !scanProgress.isScanning) {
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = BentoCardSurface
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(44.dp))
                        Text("No Music Found", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                        Text(
                            "Scan your device storage or grant audio permissions to import your tracks and albums into the player.",
                            fontSize = 13.sp,
                            color = BentoTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onScanClick,
                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan Media Files", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else if (recentlyPlayed.isEmpty()) {
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = BentoCardSurface
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(36.dp))
                        Text("Start Listening", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                        Text(
                            "Your listening statistics and personalized recommendations will appear here as you play songs.",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onOpenLibrary, colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)) {
                                Text("Open Library", color = BentoOnPrimary)
                            }
                            OutlinedButton(onClick = onQuickShuffle) {
                                Text("Shuffle Library", color = BentoTextPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}
}
}
}
}

@Composable
private fun StatsPeriodCard(
    period: StatsPeriod,
    getDurationPlayedBetween: (Long, Long) -> kotlinx.coroutines.flow.Flow<Long>,
    getTrackCountBetween: (Long, Long) -> kotlinx.coroutines.flow.Flow<Int>,
    getUniqueTrackCountBetween: (Long, Long) -> kotlinx.coroutines.flow.Flow<Int>,
    onClick: () -> Unit = {}
) {
    val pair = remember(period) {
        val todayMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        when (period) {
            StatsPeriod.TODAY -> Pair(todayMidnight, Long.MAX_VALUE)
            StatsPeriod.YESTERDAY -> {
                val yestMidnight = Calendar.getInstance().apply {
                    timeInMillis = todayMidnight
                    add(Calendar.DAY_OF_YEAR, -1)
                }.timeInMillis
                Pair(yestMidnight, todayMidnight)
            }
            StatsPeriod.WEEK -> {
                val sevenDaysAgo = Calendar.getInstance().apply {
                    timeInMillis = todayMidnight
                    add(Calendar.DAY_OF_YEAR, -6)
                }.timeInMillis
                Pair(sevenDaysAgo, Long.MAX_VALUE)
            }
            StatsPeriod.MONTH -> {
                val thirtyDaysAgo = Calendar.getInstance().apply {
                    timeInMillis = todayMidnight
                    add(Calendar.DAY_OF_YEAR, -29)
                }.timeInMillis
                Pair(thirtyDaysAgo, Long.MAX_VALUE)
            }
            StatsPeriod.ALL_TIME -> Pair(0L, Long.MAX_VALUE)
        }
    }
    val periodStartMs = pair.first
    val periodEndMs = pair.second

    val durationFlow = remember(periodStartMs, periodEndMs) { getDurationPlayedBetween(periodStartMs, periodEndMs) }
    val trackCountFlow = remember(periodStartMs, periodEndMs) { getTrackCountBetween(periodStartMs, periodEndMs) }
    val uniqueCountFlow = remember(periodStartMs, periodEndMs) { getUniqueTrackCountBetween(periodStartMs, periodEndMs) }

    val durationMs by durationFlow.collectAsStateWithLifecycle(initialValue = -1L)
    val trackCount by trackCountFlow.collectAsStateWithLifecycle(initialValue = -1)
    val uniqueCount by uniqueCountFlow.collectAsStateWithLifecycle(initialValue = -1)

    val formattedTime = remember(durationMs) {
        if (durationMs < 0L) return@remember "…"
        val totalMins = durationMs / 1000 / 60
        val hrs = totalMins / 60
        val mins = totalMins % 60
        if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
    }

    BentoCard(
        modifier = Modifier
            .width(146.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        backgroundColor = BentoCardSurface
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = period.label.uppercase(), fontSize = 10.5.sp, color = BentoPrimary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Text(text = formattedTime, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
            Text(text = if (trackCount < 0 || uniqueCount < 0) "Loading stats" else "$trackCount plays • $uniqueCount tracks", fontSize = 11.sp, color = BentoTextSecondary)
        }
    }
}
