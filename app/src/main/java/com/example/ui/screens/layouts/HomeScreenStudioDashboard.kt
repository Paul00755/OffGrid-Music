package com.example.ui.screens.layouts

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.components.AlbumArtView
import com.example.ui.components.FastScrollbar
import com.example.ui.screens.HomeFeedVisualStyle
import com.example.ui.screens.MostPlayedFilter
import com.example.ui.screens.renderHomeFeedSections
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
fun HomeScreenStudioDashboard(
    allSongs: List<Song>,
    favoriteSongs: List<Song>,
    recentlyPlayed: List<Song>,
    allPlaylists: List<Playlist>,
    playbackState: PlaybackState,
    greeting: String,
    onSongClick: (Song) -> Unit,
    onPlaySongQueue: (Song, List<Song>) -> Unit,
    onTogglePlayPause: () -> Unit,
    onQuickShuffle: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onScanClick: () -> Unit,
    onOpenSettings: (String?) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    onEditTags: ((Song) -> Unit)?,
    speedDialSongs: List<Song> = emptyList(),
    onRefreshSpeedDial: () -> Unit = {},
    homeSections: List<String> = listOf("continue_listening", "quick_actions", "listening_stats", "most_played", "most_played_playlists", "recently_played", "recently_added", "top_artists", "top_albums"),
    playbackPositionFlow: kotlinx.coroutines.flow.StateFlow<Long>? = null,
    onOpenRecentlyPlayed: () -> Unit = {},
    onOpenStatsDetail: () -> Unit = {},
    onSelectPlaylist: (Playlist) -> Unit = {},
    topArtists: List<com.example.data.model.ArtistStat> = emptyList(),
    topAlbums: List<com.example.data.model.AlbumStat> = emptyList(),
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
    val currentSong = playbackState.currentSong ?: recentlyPlayed.firstOrNull() ?: allSongs.firstOrNull()

    val dualPalette by produceState(
        initialValue = Pair(BentoPrimary, BentoSecondary),
        key1 = currentSong?.id,
        key2 = currentSong?.artworkUri,
        key3 = currentSong?.path
    ) {
        if (currentSong != null) {
            val extracted = com.example.util.ArtworkColorExtractor.extractDualGradientColorsForSong(context, currentSong)
            if (extracted != null) {
                value = extracted
            }
        }
    }

    val topAmbientColor by androidx.compose.animation.animateColorAsState(
        targetValue = dualPalette.first,
        animationSpec = tween(durationMillis = 550),
        label = "studio_deck_top_ambient"
    )
    val bottomAmbientColor by androidx.compose.animation.animateColorAsState(
        targetValue = dualPalette.second,
        animationSpec = tween(durationMillis = 550),
        label = "studio_deck_bottom_ambient"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Subtle ambient top wash inspired by Neon Studio
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            topAmbientColor.copy(alpha = 0.20f),
                            bottomAmbientColor.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Subtle, Clean & Elegant Studio Deck Header
            item(key = "studio_deck_header", contentType = "header") {
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
                            shape = RoundedCornerShape(12.dp),
                            color = BentoCardSurface.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(BentoPrimary)
                                )
                                Text(
                                    text = "STUDIO DECK",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary,
                                    letterSpacing = 1.2.sp,
                                    maxLines = 1
                                )
                            }
                            Text(
                                text = if (userAccount != null) effectiveDisplayName else greeting,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val avatarUrl = customAvatarUri ?: userAccount?.photoUrl
                        Surface(
                            onClick = { if (userAccount != null) onOpenAccount() else onOpenAuth() },
                            shape = RoundedCornerShape(12.dp),
                            color = BentoCardSurface.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (!avatarUrl.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = avatarUrl,
                                        contentDescription = "Account",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (userAccount != null) Icons.Default.Person else Icons.Default.AccountCircle,
                                        contentDescription = "Account",
                                        tint = if (userAccount != null) BentoPrimary else BentoTextPrimary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                        Surface(
                            onClick = onOpenSearch,
                            shape = RoundedCornerShape(12.dp),
                            color = BentoCardSurface.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = BentoTextPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Surface(
                            onClick = onScanClick,
                            shape = RoundedCornerShape(12.dp),
                            color = BentoCardSurface.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = BentoTextPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Surface(
                            onClick = { onOpenSettings("AUDIO") },
                            shape = RoundedCornerShape(12.dp),
                            color = BentoPrimary.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.GraphicEq, contentDescription = "DSP Equalizer", tint = BentoPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // 2. Dynamic Home Sections with Subtle Neon Studio Deck Styling
            renderHomeFeedSections(
                homeSections = homeSections,
                speedDialSongs = speedDialSongs,
                continueSong = currentSong,
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
                feedStyle = HomeFeedVisualStyle.STUDIO_DECK
            )
        }
    }
}

@Composable
internal fun StudioPlaybackVisualizer(isPlaying: Boolean) {
    // Subtle, minimal sleek line indicator instead of flashy multi-color EQ bars
    val primaryColor = BentoPrimary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(primaryColor.copy(alpha = if (isPlaying) 0.45f else 0.18f))
    )
}
