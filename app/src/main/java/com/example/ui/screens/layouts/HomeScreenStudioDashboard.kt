package com.example.ui.screens.layouts

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.ui.screens.MostPlayedFilter
import com.example.ui.screens.renderHomeFeedSections
import com.example.service.PlaybackState
import com.example.ui.components.AlbumArtView
import com.example.ui.components.BentoCard
import com.example.ui.components.SongItem
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
    val currentSong = playbackState.currentSong ?: recentlyPlayed.firstOrNull() ?: allSongs.firstOrNull()
    var selectedEqProfile by remember { mutableStateOf("FLAT") }

    val infiniteTransition = rememberInfiniteTransition(label = "studio_bars")
    val barAnim1 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
        label = "b1"
    )
    val barAnim2 by infiniteTransition.animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "b2"
    )
    val barAnim3 by infiniteTransition.animateFloat(
        initialValue = 0.15f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
        label = "b3"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(BentoCardDarkPurple)
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (userAccount != null) "Welcome, $effectiveDisplayName" else greeting,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = userAccount?.email ?: "STUDIO MASTER DECK",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary,
                            letterSpacing = 1.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val avatarUrl = customAvatarUri ?: userAccount?.photoUrl
                    IconButton(
                        onClick = { if (userAccount != null) onOpenAccount() else onOpenAuth() },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(BentoCardDarkPurple)
                    ) {
                        if (!avatarUrl.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = avatarUrl,
                                contentDescription = "Account",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(28.dp).clip(CircleShape)
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
                    IconButton(
                        onClick = onOpenSearch,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(BentoCardDarkPurple)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = BentoPrimary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { onOpenSettings("AUDIO") },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(BentoPrimary)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "DSP Equalizer", tint = BentoOnPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Live Spectrum & Master Cue Monitor Deck
        item {
            BentoCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenNowPlaying() },
                backgroundColor = BentoCardDarkPurple
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = if (playbackState.isPlaying) Color(0xFF00E676) else BentoBorder,
                                modifier = Modifier.size(8.dp)
                            ) {}
                            Text(
                                text = if (playbackState.isPlaying) "DAC ACTIVE • 320 KBPS" else "STANDBY",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (playbackState.isPlaying) Color(0xFF00E676) else BentoTextSecondary,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Text(
                            text = "OFFGRID DSP",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            letterSpacing = 1.sp
                        )
                    }

                    // Dynamic Frequency Visualizer Monitor Canvas
                    val primaryColor = BentoPrimary
                    val borderColor = BentoBorder
                    val barCount = 28
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BentoBackground)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
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
                            val isPlaying = playbackState.isPlaying
                            val barHeight = if (isPlaying) {
                                (canvasHeight * factor * ((i + 3).toFloat() / (barCount + 3))).coerceIn(4f, canvasHeight)
                            } else {
                                4f
                            }
                            val x = i * (barWidth + 3f)
                            val y = canvasHeight - barHeight

                            drawRoundRect(
                                color = if (isPlaying) primaryColor.copy(alpha = 0.4f + factor * 0.6f) else borderColor,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(2f, 2f)
                            )
                        }
                    }

                    // Track Details & Mini Master Transport
                    if (currentSong != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AlbumArtView(
                                song = currentSong,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                cornerRadius = 10
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentSong.displayTitle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = currentSong.displayArtist,
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (playbackState.currentSong?.id == currentSong.id && playbackState.durationMs > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    com.example.ui.components.RealtimeTrackProgressBar(
                                        playbackPositionFlow = playbackPositionFlow,
                                        isActive = true,
                                        durationMs = playbackState.durationMs,
                                        height = 3.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    if (playbackState.currentSong?.id == currentSong.id) {
                                        onTogglePlayPause()
                                    } else {
                                        onSongClick(currentSong)
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(BentoPrimary)
                            ) {
                                Icon(
                                    imageVector = if (playbackState.currentSong?.id == currentSong.id && playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = BentoOnPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick Equalizer Profiles Row
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DSP PROFILES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BentoPrimary, letterSpacing = 1.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val profiles = listOf("FLAT" to "Flat Studio", "BASS" to "Bass Boost", "VOCAL" to "Vocal Clarity", "ROCK" to "Rock Drive", "ELECTRONIC" to "Club Punch")
                    items(profiles.size) { idx ->
                        val (key, label) = profiles[idx]
                        val isSelected = selectedEqProfile == key
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) BentoPrimary else BentoCardDarkPurple,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) BentoPrimary else BentoBorder),
                            modifier = Modifier.clickable {
                                HapticUtils.performClickHaptic(context)
                                selectedEqProfile = key
                            }
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) BentoOnPrimary else BentoTextPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Authoritative Dynamic Home Sections
        renderHomeFeedSections(
            homeSections = homeSections,
            speedDialSongs = speedDialSongs,
            continueSong = playbackState.currentSong ?: recentlyPlayed.firstOrNull() ?: allSongs.firstOrNull(),
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
            getUniqueTrackCountBetween = getUniqueTrackCountBetween
        )
    }
}
