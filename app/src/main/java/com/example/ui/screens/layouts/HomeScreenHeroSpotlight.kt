package com.example.ui.screens.layouts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.example.ui.components.AlbumArtView
import com.example.ui.components.FastScrollbar
import com.example.ui.screens.HomeFeedVisualStyle
import com.example.ui.screens.MostPlayedFilter
import com.example.ui.screens.renderHomeFeedSections
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
fun HomeScreenHeroSpotlight(
    allSongs: List<Song>,
    favoriteSongs: List<Song>,
    recentlyPlayed: List<Song>,
    allPlaylists: List<Playlist>,
    playbackState: PlaybackState,
    scanProgress: ScanProgress,
    greeting: String,
    onSongClick: (Song) -> Unit,
    onPlaySongQueue: (Song, List<Song>) -> Unit,
    onTogglePlayPause: () -> Unit,
    onQuickShuffle: () -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onScanClick: () -> Unit,
    onOpenSettings: (String?) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecentlyPlayed: () -> Unit,
    onOpenStatsDetail: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    onEditTags: ((Song) -> Unit)?,
    topArtists: List<ArtistStat>,
    topAlbums: List<AlbumStat>,
    speedDialSongs: List<Song> = emptyList(),
    onRefreshSpeedDial: () -> Unit = {},
    homeSections: List<String> = listOf("continue_listening", "quick_actions", "listening_stats", "most_played", "most_played_playlists", "recently_played", "recently_added", "top_artists", "top_albums"),
    playbackPositionFlow: kotlinx.coroutines.flow.StateFlow<Long>? = null,
    recentlyAdded: List<Song> = emptyList(),
    mostPlayedPlaylists: List<Playlist> = emptyList(),
    mostPlayedFilteredSongs: List<Song> = emptyList(),
    selectedMostPlayedFilter: MostPlayedFilter = MostPlayedFilter.SEVEN_DAYS,
    onSelectMostPlayedFilter: (MostPlayedFilter) -> Unit = {},
    getDurationPlayedBetween: (Long, Long) -> kotlinx.coroutines.flow.Flow<Long> = { _, _ -> kotlinx.coroutines.flow.flowOf(0L) },
    getTrackCountBetween: (Long, Long) -> kotlinx.coroutines.flow.Flow<Int> = { _, _ -> kotlinx.coroutines.flow.flowOf(0) },
    getUniqueTrackCountBetween: (Long, Long) -> kotlinx.coroutines.flow.Flow<Int> = { _, _ -> kotlinx.coroutines.flow.flowOf(0) },
    userAccount: com.example.auth.UserAccount? = null,
    effectiveDisplayName: String = "Music Lover",
    customAvatarUri: String? = null,
    onOpenAccount: () -> Unit = {},
    onOpenAuth: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val featuredSong = playbackState.currentSong ?: recentlyPlayed.firstOrNull() ?: allSongs.firstOrNull()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(com.example.ui.theme.BentoScreenBackground)
    ) {

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. Floating Glassmorphic Spotlight Header
            item(key = "hero_spotlight_header", contentType = "header") {
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
                            onClick = onOpenDrawer,
                            shape = CircleShape,
                            color = BentoCardSurface.copy(alpha = 0.78f),
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open Sidebar Menu",
                                    tint = BentoTextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = if (allSongs.isEmpty()) "OffGrid Music" else "HERO SPOTLIGHT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoPrimary,
                                letterSpacing = 1.3.sp,
                                maxLines = 1
                            )
                            Text(
                                text = if (userAccount != null) "Welcome, $effectiveDisplayName" else greeting,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        val avatarUrl = customAvatarUri ?: userAccount?.photoUrl
                        Surface(
                            onClick = { if (userAccount != null) onOpenAccount() else onOpenAuth() },
                            shape = CircleShape,
                            color = BentoCardSurface.copy(alpha = 0.78f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (!avatarUrl.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = avatarUrl,
                                        contentDescription = "Account",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (userAccount != null) Icons.Default.Person else Icons.Default.AccountCircle,
                                        contentDescription = "Account",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                        Surface(
                            onClick = onOpenSearch,
                            shape = CircleShape,
                            color = BentoCardSurface.copy(alpha = 0.78f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = BentoPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Surface(
                            onClick = onScanClick,
                            shape = CircleShape,
                            color = BentoCardSurface.copy(alpha = 0.78f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = BentoPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Surface(
                            onClick = { onOpenSettings(null) },
                            shape = CircleShape,
                            color = BentoPrimary,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = BentoOnPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            if (scanProgress.isScanning || scanProgress.status == com.example.scanner.ScanStatus.SCANNING) {
                item(key = "hero_scanning_banner", contentType = "scanning_banner") {
                    com.example.ui.components.BentoCard(
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
                                    text = "${scanProgress.validTracksFound} tracks indexed",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BentoTextSecondary
                                )
                            }
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

            // 2. Authoritative Dynamic Home Sections with Hero Spotlight Component Styling
            renderHomeFeedSections(
                homeSections = homeSections,
                speedDialSongs = speedDialSongs,
                continueSong = featuredSong,
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
                onSelectMostPlayedFilter = onSelectMostPlayedFilter,
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
                getUniqueTrackCountBetween = getUniqueTrackCountBetween,
                feedStyle = HomeFeedVisualStyle.HERO_SPOTLIGHT
            )
        }

        FastScrollbar(
            listState = listState,
            totalItems = listState.layoutInfo.totalItemsCount.coerceAtLeast(homeSections.size + 2),
            itemIndexToLabel = null,
            minItemsToShow = 4,
            bottomPadding = 120.dp,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun SpotlightCategoryCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: String?,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = BentoCardSurface.copy(alpha = 0.80f),
        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(icon, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
            if (!badge.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = BentoPrimary.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = badge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoPrimary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
