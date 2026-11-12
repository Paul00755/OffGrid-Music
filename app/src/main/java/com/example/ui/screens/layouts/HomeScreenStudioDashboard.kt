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

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Pro-Audio Studio Rack Header
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
                            shape = RoundedCornerShape(8.dp),
                            color = BentoCardSurface,
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E676))
                                )
                                Text(
                                    text = "STUDIO DECK // DSP-24",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoPrimary,
                                    letterSpacing = 1.1.sp,
                                    maxLines = 1
                                )
                            }
                            Text(
                                text = if (userAccount != null) effectiveDisplayName else greeting,
                                fontSize = 18.sp,
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
                            shape = RoundedCornerShape(8.dp),
                            color = BentoCardSurface,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (!avatarUrl.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = avatarUrl,
                                        contentDescription = "Account",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
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
                            shape = RoundedCornerShape(8.dp),
                            color = BentoCardSurface,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = BentoPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Surface(
                            onClick = onScanClick,
                            shape = RoundedCornerShape(8.dp),
                            color = BentoCardSurface,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = BentoPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Surface(
                            onClick = { onOpenSettings("AUDIO") },
                            shape = RoundedCornerShape(8.dp),
                            color = BentoPrimary,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.GraphicEq, contentDescription = "DSP Equalizer", tint = BentoOnPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // 2. Authoritative Dynamic Home Sections with Studio Deck Channel-Strip Styling
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
    val (barAnim1, barAnim2, barAnim3) = if (isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "studio_bars")
        val b1 by infiniteTransition.animateFloat(
            initialValue = 0.22f, targetValue = 0.95f,
            animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
            label = "b1"
        )
        val b2 by infiniteTransition.animateFloat(
            initialValue = 0.35f, targetValue = 0.82f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
            label = "b2"
        )
        val b3 by infiniteTransition.animateFloat(
            initialValue = 0.18f, targetValue = 0.90f,
            animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
            label = "b3"
        )
        Triple(b1, b2, b3)
    } else {
        Triple(0.22f, 0.35f, 0.18f)
    }

    val primaryColor = BentoPrimary
    val peakColor = BentoAccentPinkText
    val ledGreen = Color(0xFF00E676)
    val borderColor = BentoBorder
    val barCount = 36
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BentoBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val totalGap = (barCount - 1) * 3f
        val barWidth = (canvasWidth - totalGap) / barCount

        for (i in 0 until barCount) {
            val factor = when (i % 3) {
                0 -> barAnim1
                1 -> barAnim2
                else -> barAnim3
            }
            val normalizedRatio = (factor * ((i + 6).toFloat() / (barCount + 6))).coerceIn(0.12f, 1f)
            val barHeight = if (isPlaying) {
                (canvasHeight * normalizedRatio).coerceIn(5f, canvasHeight)
            } else {
                5f
            }
            val x = i * (barWidth + 3f)
            val y = canvasHeight - barHeight

            val barColor = when {
                !isPlaying -> borderColor
                normalizedRatio > 0.78f -> peakColor
                normalizedRatio > 0.48f -> primaryColor
                else -> ledGreen
            }

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}
