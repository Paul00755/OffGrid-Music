package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.example.service.PlaybackState
import com.example.ui.components.AlbumArtView
import com.example.ui.components.BentoCard
import com.example.ui.components.SongItem
import com.example.ui.theme.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

/**
 * Authoritative global Home Feed Sections renderer.
 * Shared across ALL Home screen variants to guarantee identical feed selection and ordering.
 */
fun LazyListScope.renderHomeFeedSections(
    homeSections: List<String>,
    speedDialSongs: List<Song>,
    continueSong: Song?,
    playbackState: PlaybackState,
    playbackPositionFlow: StateFlow<Long>?,
    allSongs: List<Song>,
    favoriteSongs: List<Song>,
    recentlyPlayed: List<Song>,
    recentlyAdded: List<Song>,
    topArtists: List<ArtistStat>,
    topAlbums: List<AlbumStat>,
    allPlaylists: List<Playlist>,
    mostPlayedPlaylists: List<Playlist>,
    mostPlayedFilteredSongs: List<Song>,
    selectedMostPlayedFilter: MostPlayedFilter,
    onSelectMostPlayedFilter: (MostPlayedFilter) -> Unit,
    onPlaySongQueue: (Song, List<Song>) -> Unit,
    onRefreshSpeedDial: () -> Unit,
    onSongClick: (Song) -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    onEditTags: ((Song) -> Unit)?,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onOpenRecentlyPlayed: () -> Unit,
    onOpenStatsDetail: () -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onQuickShuffle: () -> Unit,
    getDurationPlayedBetween: (Long, Long) -> Flow<Long>,
    getTrackCountBetween: (Long, Long) -> Flow<Int>,
    getUniqueTrackCountBetween: (Long, Long) -> Flow<Int>
) {
    homeSections.map { normalizeHomeSectionKey(it) }.distinct().forEach { sectionKey ->
        when (sectionKey) {
            "speed_dial" -> {
                if (speedDialSongs.isNotEmpty()) {
                    item(key = "section_speed_dial") {
                        com.example.ui.components.HomeScreenSpeedDial(
                            speedDialSongs = speedDialSongs,
                            playbackState = playbackState,
                            onSongClick = { song, queue -> onPlaySongQueue(song, queue) },
                            onPlayAll = { queue ->
                                if (queue.isNotEmpty()) {
                                    onPlaySongQueue(queue.first(), queue)
                                }
                            },
                            onRefresh = onRefreshSpeedDial
                        )
                    }
                }
            }
            "continue_listening" -> {
                if (continueSong != null) {
                    val isCurrentActive = playbackState.currentSong?.id == continueSong.id
                    val duration = if (isCurrentActive && playbackState.durationMs > 0) {
                        playbackState.durationMs
                    } else if (continueSong.durationMs > 0) {
                        continueSong.durationMs
                    } else {
                        playbackState.durationMs
                    }

                    item(key = "section_continue_listening") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Continue Listening",
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary
                            )
                            if (isCurrentActive && playbackState.isPlaying) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(BentoPrimary, CircleShape)
                                    )
                                    Text(
                                        text = "PLAYING NOW",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        BentoCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenNowPlaying() },
                            backgroundColor = BentoCardSurface
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumArtView(
                                    song = continueSong,
                                    modifier = Modifier.size(60.dp),
                                    cornerRadius = 12
                                )

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = continueSong.displayTitle,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = continueSong.displayArtist,
                                        fontSize = 12.5.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    if (duration > 0) {
                                        com.example.ui.components.RealtimeTrackProgressBar(
                                            playbackPositionFlow = playbackPositionFlow,
                                            isActive = isCurrentActive,
                                            durationMs = duration,
                                            showTimestamps = isCurrentActive,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Surface(
                                    shape = CircleShape,
                                    color = BentoPrimary,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable {
                                            if (playbackState.currentSong?.id == continueSong.id) {
                                                onTogglePlayPause()
                                            } else {
                                                onSongClick(continueSong)
                                            }
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (playbackState.currentSong?.id == continueSong.id && playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
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
            "quick_actions" -> {
                item(key = "section_quick_actions") {
                    val context = LocalContext.current
                    Text(
                        text = "Quick Actions",
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BentoCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (allSongs.isNotEmpty()) {
                                        onQuickShuffle()
                                        android.widget.Toast.makeText(context, "Shuffling all ${allSongs.size} tracks", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "No songs in library to shuffle", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                            backgroundColor = BentoCardSurface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = BentoPrimary.copy(alpha = 0.10f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.20f)),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(imageVector = Icons.Default.Shuffle, contentDescription = "Shuffle All", tint = BentoPrimary, modifier = Modifier.size(19.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text("Shuffle All Tracks", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${allSongs.size} songs in library", fontSize = 11.5.sp, color = BentoTextSecondary, maxLines = 1)
                                    }
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = BentoPrimary.copy(alpha = 0.12f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.30f)),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play Shuffled", tint = BentoPrimary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BentoCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenLibrary() },
                                backgroundColor = BentoCardSurface
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(BentoPrimary.copy(alpha = 0.08f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(17.dp))
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Library", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                }
                            }

                            BentoCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenPlaylists() },
                                backgroundColor = BentoCardSurface
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(BentoPrimary.copy(alpha = 0.08f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(imageVector = Icons.Default.QueueMusic, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(17.dp))
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Playlists", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                }
                            }

                            BentoCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenFavorites() },
                                backgroundColor = BentoCardSurface
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(BentoAccentPinkText.copy(alpha = 0.10f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(imageVector = Icons.Default.Favorite, contentDescription = null, tint = BentoAccentPinkText, modifier = Modifier.size(17.dp))
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Favorites", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                }
                            }
                        }
                    }
                }
            }
            "listening_stats" -> {
                item(key = "section_listening_stats") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Listening Statistics",
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenStatsDetail() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "view in detail →",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = BentoPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(StatsPeriod.values(), key = { it.name }) { period ->
                            HomeStatsPeriodCard(
                                period = period,
                                getDurationPlayedBetween = getDurationPlayedBetween,
                                getTrackCountBetween = getTrackCountBetween,
                                getUniqueTrackCountBetween = getUniqueTrackCountBetween,
                                onClick = onOpenStatsDetail
                            )
                        }
                    }
                }
            }
            "most_played" -> {
                item(key = "section_most_played_header") {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Most Played",
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(MostPlayedFilter.values()) { filter ->
                                val isSelected = filter == selectedMostPlayedFilter
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectMostPlayedFilter(filter) },
                                    label = { Text(filter.label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = BentoPrimary.copy(alpha = 0.14f),
                                        selectedLabelColor = BentoPrimary,
                                        containerColor = BentoCardSurface,
                                        labelColor = BentoTextSecondary
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = if (isSelected) BentoPrimary.copy(alpha = 0.35f) else BentoBorder.copy(alpha = 0.28f)
                                    )
                                )
                            }
                        }
                    }
                }

                if (mostPlayedFilteredSongs.isNotEmpty()) {
                    items(mostPlayedFilteredSongs, key = { it.id }, contentType = { "song_item" }) { song ->
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            SongItem(
                                song = song,
                                isPlaying = playbackState.currentSong?.id == song.id,
                                onClick = { onPlaySongQueue(song, mostPlayedFilteredSongs) },
                                onFavoriteToggle = { onFavoriteToggle(song) },
                                onMoreClick = { showMenu = true }
                            )

                            if (showMenu) {
                                com.example.ui.components.SongActionMenu(
                                    expanded = true,
                                    song = song,
                                    onDismissRequest = { showMenu = false },
                                    onPlayNow = { onPlaySongQueue(song, mostPlayedFilteredSongs) },
                                    onPlayNext = { onPlayNext(song) },
                                    onAddToQueue = { onAddToQueue(song) },
                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                    onFavoriteToggle = { onFavoriteToggle(song) },
                                    onEditTags = if (onEditTags != null) { { onEditTags(song) } } else null,
                                    onDeleteSong = { onDeleteSong(song) }
                                )
                            }
                        }
                    }
                } else {
                    item(key = "section_most_played_empty") {
                        Text(
                            text = "No play history recorded for this period yet.",
                            fontSize = 13.sp,
                            color = BentoTextSecondary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            "most_played_playlists" -> {
                val playlistsToShow = if (mostPlayedPlaylists.isNotEmpty()) mostPlayedPlaylists else allPlaylists
                if (playlistsToShow.isNotEmpty()) {
                    item(key = "section_most_played_playlists") {
                        Text(
                            text = "Most Played Playlists",
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(playlistsToShow, key = { "pl_${it.id}" }) { playlist ->
                                BentoCard(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .clickable { onSelectPlaylist(playlist) },
                                    backgroundColor = BentoCardSurface
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        com.example.ui.components.PlaylistCover(
                                            playlist = playlist,
                                            playlistSongs = emptyList(),
                                            modifier = Modifier.size(90.dp),
                                            cornerRadius = 12
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = playlist.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${playlist.songCount} songs",
                                            fontSize = 11.sp,
                                            color = BentoTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "recently_played" -> {
                if (recentlyPlayed.isNotEmpty()) {
                    item(key = "section_recently_played") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenRecentlyPlayed() }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recently Played",
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "See all",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BentoPrimary
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "View all recently played",
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        val uniqueRecent = remember(recentlyPlayed) {
                            val seen = mutableSetOf<String>()
                            recentlyPlayed.filter {
                                val key = if (it.id > 0) "id_${it.id}" else "path_${it.path}"
                                seen.add(key)
                            }.take(10)
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = uniqueRecent,
                                key = { it.id },
                                contentType = { "recent_song" }
                            ) { song ->
                                Column(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onPlaySongQueue(song, uniqueRecent) }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(110.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    ) {
                                        AlbumArtView(
                                            song = song,
                                            modifier = Modifier.fillMaxSize(),
                                            cornerRadius = 12
                                        )
                                        if (song.playCount > 0) {
                                            Surface(
                                                shape = RoundedCornerShape(topStart = 0.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 0.dp),
                                                color = Color.Black.copy(alpha = 0.72f),
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(bottom = 4.dp, start = 4.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = null,
                                                        tint = BentoPrimary,
                                                        modifier = Modifier.size(10.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Text(
                                                        text = "${song.playCount}",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = song.displayTitle,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.displayArtist,
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            "recently_added" -> {
                if (recentlyAdded.isNotEmpty()) {
                    item(key = "section_recently_added") {
                        Text(
                            text = "Recently Added",
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(recentlyAdded.take(10), key = { it.id }, contentType = { "recent_added" }) { song ->
                                Column(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .clickable { onPlaySongQueue(song, recentlyAdded) }
                                ) {
                                    AlbumArtView(
                                        song = song,
                                        modifier = Modifier.size(110.dp),
                                        cornerRadius = 12
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = song.displayTitle,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.displayArtist,
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            "top_artists" -> {
                if (topArtists.isNotEmpty()) {
                    item(key = "section_top_artists") {
                        Text(
                            text = "Top Artists",
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(topArtists, key = { "art_${it.name}" }) { artist ->
                                BentoCard(
                                    modifier = Modifier.width(130.dp),
                                    backgroundColor = BentoCardSurface
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        com.example.ui.components.ArtistAvatar(
                                            artistName = artist.name,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = artist.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${artist.playCount} plays",
                                            fontSize = 11.sp,
                                            color = BentoTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "top_albums" -> {
                if (topAlbums.isNotEmpty()) {
                    item(key = "section_top_albums") {
                        Text(
                            text = "Top Albums",
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(topAlbums, key = { "alb_${it.name}" }) { album ->
                                BentoCard(
                                    modifier = Modifier.width(130.dp),
                                    backgroundColor = BentoCardSurface
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        val dummySongForArt = remember(album) {
                                            Song(
                                                id = 0L,
                                                title = album.name,
                                                artist = album.artist,
                                                album = album.name,
                                                durationMs = 0L,
                                                path = "",
                                                contentUriString = "",
                                                artworkUri = album.artworkUri
                                            )
                                        }
                                        AlbumArtView(
                                            song = dummySongForArt,
                                            modifier = Modifier.size(80.dp),
                                            cornerRadius = 10
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = album.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${album.playCount} plays",
                                            fontSize = 11.sp,
                                            color = BentoTextSecondary
                                        )
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
fun HomeStatsPeriodCard(
    period: StatsPeriod,
    getDurationPlayedBetween: (Long, Long) -> Flow<Long>,
    getTrackCountBetween: (Long, Long) -> Flow<Int>,
    getUniqueTrackCountBetween: (Long, Long) -> Flow<Int>,
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
