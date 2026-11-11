package com.example.ui.screens.layouts

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
fun HomeScreenMinimalFeed(
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
    val currentSong = playbackState.currentSong ?: recentlyPlayed.firstOrNull() ?: allSongs.firstOrNull()
    val listState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Frameless Editorial Typographic Header (Zero Boxy Cards!)
            item(key = "minimal_stream_header", contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onOpenDrawer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Menu",
                                    tint = BentoTextPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "MINIMAL STREAM",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoPrimary,
                                    letterSpacing = 1.6.sp
                                )
                                Text(
                                    text = if (userAccount != null) effectiveDisplayName else greeting,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Light,
                                    color = BentoTextPrimary,
                                    letterSpacing = (-0.5).sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val avatarUrl = customAvatarUri ?: userAccount?.photoUrl
                            IconButton(
                                onClick = { if (userAccount != null) onOpenAccount() else onOpenAuth() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                if (!avatarUrl.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = avatarUrl,
                                        contentDescription = "Account",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.size(26.dp).clip(CircleShape)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (userAccount != null) Icons.Default.Person else Icons.Default.AccountCircle,
                                        contentDescription = "Account",
                                        tint = if (userAccount != null) BentoPrimary else BentoTextPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            IconButton(onClick = onOpenSearch, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onQuickShuffle, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = BentoPrimary, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onOpenSettings(null) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = BentoTextSecondary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = BentoBorder.copy(alpha = 0.5f))
                }
            }

            // 2. Authoritative Dynamic Home Sections with Frameless Minimal Stream Styling
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
                feedStyle = HomeFeedVisualStyle.MINIMAL_STREAM
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
private fun MinimalEditorialNavTab(
    label: String,
    count: Int?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = BentoTextPrimary,
            letterSpacing = 1.sp
        )
        if (count != null) {
            Text(
                text = "/$count",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoPrimary
            )
        }
    }
}
