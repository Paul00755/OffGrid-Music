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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    var selectedEqProfile by remember { mutableStateOf("FLAT") }

    // Deduplicate continue_listening since the Studio Master Cue Monitor already shows the active track
    val effectiveSections = remember(homeSections, currentSong) {
        if (currentSong != null) {
            homeSections.filterNot { it.equals("continue_listening", ignoreCase = true) }
        } else {
            homeSections
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Studio Rack Header
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
                            color = BentoCardSurface,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "STUDIO DECK • 24-BIT DSP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoPrimary,
                                letterSpacing = 1.1.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (userAccount != null) "Welcome, $effectiveDisplayName" else greeting,
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
                            shape = CircleShape,
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
                        }
                        Surface(
                            onClick = onOpenSearch,
                            shape = CircleShape,
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
                            shape = CircleShape,
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
                            shape = CircleShape,
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

            // 2. Live Spectrum & Master Cue Monitor Console
            item(key = "studio_master_cue_deck", contentType = "studio_deck") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.5.dp, BentoPrimary.copy(alpha = 0.38f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNowPlaying() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Top Telemetry Strip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (playbackState.isPlaying) Color(0xFF00E676) else BentoBorder,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Text(
                                    text = if (playbackState.isPlaying) "DAC ACTIVE • 48kHz / 32-BIT" else "STUDIO MONITOR STANDBY",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (playbackState.isPlaying) Color(0xFF00E676) else BentoTextSecondary,
                                    letterSpacing = 0.7.sp
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BentoCardDarkPurple,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "MASTER CUE",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary,
                                    letterSpacing = 0.9.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        // Spectrum Analyzer Visualizer
                        StudioPlaybackVisualizer(isPlaying = playbackState.isPlaying)

                        // Active Track Monitor + Cue Transport
                        if (currentSong != null) {
                            val isCurrentActive = playbackState.currentSong?.id == currentSong.id
                            val isFav = favoriteSongs.any { it.id == currentSong.id }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AlbumArtView(
                                    song = currentSong,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    cornerRadius = 12
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentSong.displayTitle,
                                        fontSize = 15.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${currentSong.displayArtist} • ${currentSong.displayAlbum}",
                                        fontSize = 12.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isCurrentActive && playbackState.durationMs > 0) {
                                        Spacer(modifier = Modifier.height(6.dp))
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
                                        onFavoriteToggle(currentSong)
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Favorite",
                                        tint = if (isFav) BentoAccentPinkText else BentoTextSecondary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        if (isCurrentActive) {
                                            onTogglePlayPause()
                                        } else {
                                            onSongClick(currentSong)
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = BentoPrimary,
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isCurrentActive && playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
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
            }

            // 3. Studio Quick Launch Pads & DSP Profile Selector
            item(key = "studio_pads_and_dsp", contentType = "studio_controls") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 4-Pad Studio Quick Launch Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StudioLaunchPad(
                            icon = Icons.Default.LibraryMusic,
                            title = "Tracks",
                            subtitle = "${allSongs.size}",
                            onClick = onOpenLibrary,
                            modifier = Modifier.weight(1f)
                        )
                        StudioLaunchPad(
                            icon = Icons.Default.Favorite,
                            title = "Liked",
                            subtitle = "${favoriteSongs.size}",
                            onClick = onOpenFavorites,
                            modifier = Modifier.weight(1f)
                        )
                        StudioLaunchPad(
                            icon = Icons.Default.QueueMusic,
                            title = "Mixes",
                            subtitle = "${allPlaylists.size}",
                            onClick = onOpenPlaylists,
                            modifier = Modifier.weight(1f)
                        )
                        StudioLaunchPad(
                            icon = Icons.Default.BarChart,
                            title = "Analytics",
                            subtitle = "Studio",
                            onClick = onOpenStatsDetail,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // DSP Profiles Rack Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DSP ENGINE PRESETS",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoPrimary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Open Equalizer →",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextSecondary,
                            modifier = Modifier.clickable { onOpenSettings("AUDIO") }
                        )
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val profiles = listOf(
                            "FLAT" to "Flat Studio",
                            "BASS" to "Bass Boost",
                            "VOCAL" to "Vocal Clarity",
                            "ROCK" to "Rock Drive",
                            "ELECTRONIC" to "Club Punch"
                        )
                        items(profiles.size, key = { profiles[it].first }) { idx ->
                            val (key, label) = profiles[idx]
                            val isSelected = selectedEqProfile == key
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) BentoPrimary else BentoCardSurface,
                                border = BorderStroke(1.dp, if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.55f)),
                                modifier = Modifier.clickable {
                                    HapticUtils.performClickHaptic(context)
                                    selectedEqProfile = key
                                    onOpenSettings("AUDIO")
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Equalizer,
                                        contentDescription = null,
                                        tint = if (isSelected) BentoOnPrimary else BentoPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) BentoOnPrimary else BentoTextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Authoritative Dynamic Home Sections
            renderHomeFeedSections(
                homeSections = effectiveSections,
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
                getUniqueTrackCountBetween = getUniqueTrackCountBetween
            )
        }

        FastScrollbar(
            listState = listState,
            totalItems = effectiveSections.size + 4,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(bottom = 100.dp, end = 2.dp)
        )
    }
}

@Composable
private fun StudioLaunchPad(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = title, tint = BentoPrimary, modifier = Modifier.size(18.dp))
            Text(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = BentoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoTextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StudioPlaybackVisualizer(isPlaying: Boolean) {
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
    val borderColor = BentoBorder
    val barCount = 32
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(12.dp))
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
            val barHeight = if (isPlaying) {
                (canvasHeight * factor * ((i + 4).toFloat() / (barCount + 4))).coerceIn(5f, canvasHeight)
            } else {
                5f
            }
            val x = i * (barWidth + 3f)
            val y = canvasHeight - barHeight

            drawRoundRect(
                color = if (isPlaying) primaryColor.copy(alpha = 0.42f + factor * 0.58f) else borderColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2.5f, 2.5f)
            )
        }
    }
}
