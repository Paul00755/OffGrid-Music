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

    // Deduplicate continue_listening when Hero Spotlight card already features the active song
    val effectiveSections = remember(homeSections, featuredSong) {
        if (featuredSong != null) {
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. Top Spotlight Header
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
                            shape = RoundedCornerShape(14.dp),
                            color = BentoCardSurface,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                            modifier = Modifier.size(40.dp)
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
                                text = "HERO SPOTLIGHT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoPrimary,
                                letterSpacing = 1.1.sp,
                                maxLines = 1
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

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        val avatarUrl = customAvatarUri ?: userAccount?.photoUrl
                        Surface(
                            onClick = { if (userAccount != null) onOpenAccount() else onOpenAuth() },
                            shape = CircleShape,
                            color = BentoCardSurface,
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
                            color = BentoCardSurface,
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
                            color = BentoCardSurface,
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

            // 2. Hero Spotlight Showcase Card
            if (featuredSong != null) {
                item(key = "hero_spotlight_banner_${featuredSong.id}", contentType = "hero_banner") {
                    val isCurrentActive = playbackState.currentSong?.id == featuredSong.id
                    val isFav = favoriteSongs.any { it.id == featuredSong.id }

                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.5.dp, BentoPrimary.copy(alpha = 0.42f)),
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenNowPlaying() }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            BentoPrimary.copy(alpha = 0.22f),
                                            BentoCardDarkPurple.copy(alpha = 0.96f),
                                            BentoCardSurface
                                        )
                                    )
                                )
                                .padding(20.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                // Top Badge + Actions Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = BentoPrimary.copy(alpha = 0.20f),
                                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isCurrentActive && playbackState.isPlaying) BentoPrimary else BentoTextSecondary)
                                            )
                                            Text(
                                                text = if (isCurrentActive && playbackState.isPlaying) "LIVE SPOTLIGHT" else "FEATURED TRACK",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = BentoPrimary,
                                                letterSpacing = 1.sp
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                onAddToQueue(featuredSong)
                                            },
                                            shape = CircleShape,
                                            color = BentoCardDarkPurple,
                                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.QueueMusic,
                                                    contentDescription = "Add to Queue",
                                                    tint = BentoTextPrimary,
                                                    modifier = Modifier.size(17.dp)
                                                )
                                            }
                                        }

                                        Surface(
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                onFavoriteToggle(featuredSong)
                                            },
                                            shape = CircleShape,
                                            color = if (isFav) BentoAccentPinkText.copy(alpha = 0.16f) else BentoCardDarkPurple,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isFav) BentoAccentPinkText.copy(alpha = 0.5f) else BentoBorder.copy(alpha = 0.45f)
                                            ),
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                    contentDescription = "Favorite",
                                                    tint = if (isFav) BentoAccentPinkText else BentoTextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Artwork + Track Metadata
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                                        shadowElevation = 6.dp,
                                        modifier = Modifier.size(106.dp)
                                    ) {
                                        AlbumArtView(
                                            song = featuredSong,
                                            modifier = Modifier.fillMaxSize(),
                                            cornerRadius = 20
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = featuredSong.displayTitle,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = BentoTextPrimary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = featuredSong.displayArtist,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (featuredSong.displayAlbum.isNotEmpty()) {
                                            Text(
                                                text = featuredSong.displayAlbum,
                                                fontSize = 12.sp,
                                                color = BentoTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                // Live Progress Strip
                                if (isCurrentActive && playbackState.durationMs > 0) {
                                    com.example.ui.components.RealtimeTrackProgressBar(
                                        playbackPositionFlow = playbackPositionFlow,
                                        isActive = true,
                                        durationMs = playbackState.durationMs,
                                        height = 3.5.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // Primary Spotlight Action Bar
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = BentoPrimary,
                                        modifier = Modifier
                                            .weight(1.35f)
                                            .height(44.dp)
                                            .clickable {
                                                HapticUtils.performClickHaptic(context)
                                                if (isCurrentActive) {
                                                    onTogglePlayPause()
                                                } else {
                                                    onSongClick(featuredSong)
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (isCurrentActive && playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = BentoOnPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isCurrentActive && playbackState.isPlaying) "Pause Spotlight" else "Play Spotlight",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoOnPrimary
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = BentoCardDarkPurple,
                                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.6f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .clickable {
                                                HapticUtils.performClickHaptic(context)
                                                onQuickShuffle()
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = BentoPrimary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Shuffle All", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Quick Category Bento Pills
            item(key = "hero_spotlight_categories", contentType = "categories") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    item(key = "cat_lib") {
                        CategoryPill(icon = Icons.Default.LibraryMusic, label = "Library", badge = "${allSongs.size}", onClick = onOpenLibrary)
                    }
                    item(key = "cat_fav") {
                        CategoryPill(icon = Icons.Default.Favorite, label = "Favorites", badge = "${favoriteSongs.size}", onClick = onOpenFavorites)
                    }
                    item(key = "cat_pl") {
                        CategoryPill(icon = Icons.Default.QueueMusic, label = "Playlists", badge = "${allPlaylists.size}", onClick = onOpenPlaylists)
                    }
                    item(key = "cat_rec") {
                        CategoryPill(icon = Icons.Default.History, label = "Recent", badge = null, onClick = onOpenRecentlyPlayed)
                    }
                    item(key = "cat_stats") {
                        CategoryPill(icon = Icons.Default.BarChart, label = "Stats Studio", badge = null, onClick = onOpenStatsDetail)
                    }
                }
            }

            // 4. Authoritative Dynamic Home Sections
            renderHomeFeedSections(
                homeSections = effectiveSections,
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
private fun CategoryPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: String?,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(icon, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
            if (!badge.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = BentoPrimary.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = badge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
