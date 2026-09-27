package com.example.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    homeSections: List<String> = listOf("speed_dial", "continue_listening", "quick_actions", "listening_stats", "most_played", "most_played_playlists", "recently_played", "recently_added", "top_artists", "top_albums"),
    playbackPositionFlow: kotlinx.coroutines.flow.StateFlow<Long>? = null,
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

    val mostPlayedFilteredSongs by mostPlayedFlow.collectAsState(initial = emptyList())
    val topArtists by topArtistsFlow.collectAsState(initial = emptyList())
    val topAlbums by topAlbumsFlow.collectAsState(initial = emptyList())

    // Current/Last Song for "Continue Listening"
    val continueSong = playbackState.currentSong ?: recentlyPlayed.firstOrNull() ?: allSongs.firstOrNull()

    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 4..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..22 -> "Good Evening"
            else -> "Late Night Vibes"
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = BentoCardSurface,
                drawerContentColor = BentoTextPrimary,
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 340.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
            ) {
                // Sidebar Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BentoPrimary.copy(alpha = 0.08f))
                        .padding(horizontal = 20.dp, vertical = 22.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "OFFGRID MUSIC",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary,
                            letterSpacing = 0.6.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${allSongs.size} Songs • ${allPlaylists.size} Playlists",
                        fontSize = 12.sp,
                        color = BentoTextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Sidebar Menu Items with Dotted Line Dividers
                SidebarMenuItem(
                    icon = Icons.Default.BarChart,
                    label = "Listening Stats & Insights",
                    badge = "Stats",
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenStatsDetail()
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Search,
                    label = "Search Songs & Artists",
                    badge = null,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenSearch()
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Equalizer,
                    label = "Equalizer & Sound Effects",
                    badge = "Graph EQ",
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenSettings("AUDIO")
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Storage,
                    label = "Offline Cache & Storage",
                    badge = null,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenSettings("CACHE")
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Palette,
                    label = "UI & Visual Appearance",
                    badge = null,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenSettings("UI")
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Settings,
                    label = "All Settings & Audio Preferences",
                    badge = null,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenSettings("ALL")
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
                        coroutineScope.launch { drawerState.close() }
                        onOpenSettings("SLEEP_TIMER")
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.QueueMusic,
                    label = "Playlists",
                    badge = "${allPlaylists.size}",
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenPlaylists()
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.LibraryMusic,
                    label = "Music Library",
                    badge = "${allSongs.size}",
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenLibrary()
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Favorite,
                    label = "Favorite Songs",
                    badge = "${favoriteSongs.size}",
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenFavorites()
                    }
                )
                DottedDivider()

                SidebarMenuItem(
                    icon = Icons.Default.Refresh,
                    label = "Recently Played",
                    badge = if (recentlyPlayed.isNotEmpty()) "${recentlyPlayed.size}" else null,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenRecentlyPlayed()
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
                    onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
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
                    onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
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
                    onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
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
                    modifier = modifier
                )
            }
            com.example.ui.theme.HomeScreenLayout.BENTO_DYNAMIC -> {
                LazyColumn(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                // Header Row with Time-Based Greeting, Sidebar Drawer Toggle, and Top Nav Search Option
                item {
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
                                onClick = { coroutineScope.launch { drawerState.open() } },
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
                                    text = greeting,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "OffGrid Music",
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                text = "${scanProgress.displayTracksCount} tracks found",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = BentoTextSecondary
                            )
                        }
                        Text(
                            text = if (scanProgress.currentFolder.isNotBlank()) "Scanning in progress (${scanProgress.currentFolder})..." else "Scanning in progress...",
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

    val durationMs by durationFlow.collectAsState(initial = 0L)
    val trackCount by trackCountFlow.collectAsState(initial = 0)
    val uniqueCount by uniqueCountFlow.collectAsState(initial = 0)

    val formattedTime = remember(durationMs) {
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
            Text(text = "$trackCount plays • $uniqueCount tracks", fontSize = 11.sp, color = BentoTextSecondary)
        }
    }
}
