package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

enum class HomeFeedVisualStyle {
    BENTO_DEFAULT,
    HERO_SPOTLIGHT,
    MINIMAL_STREAM,
    STUDIO_DECK
}

/**
 * Authoritative global Home Feed Sections renderer.
 * Shared across ALL Home screen variants to guarantee identical feed selection and ordering,
 * while supporting distinct component styling per Home screen layout.
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
    getUniqueTrackCountBetween: (Long, Long) -> Flow<Int>,
    feedStyle: HomeFeedVisualStyle = HomeFeedVisualStyle.BENTO_DEFAULT
) {
    val currentPlayingSongId = playbackState.currentSong?.id
    homeSections.map { normalizeHomeSectionKey(it) }.distinct().forEach { sectionKey ->
        when (sectionKey) {
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
                        HomeFeedStyledHeader(
                            title = "Continue Listening",
                            studioCode = "CH // RESUME CUE",
                            feedStyle = feedStyle,
                            trailingContent = if (isCurrentActive && playbackState.isPlaying) {
                                {
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
                            } else null
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        when (feedStyle) {
                            HomeFeedVisualStyle.MINIMAL_STREAM -> {
                                // Frameless editorial resume strip
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenNowPlaying() }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        AlbumArtView(
                                            song = continueSong,
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape),
                                            cornerRadius = 50
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = continueSong.displayTitle,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = BentoTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = continueSong.displayArtist,
                                                fontSize = 12.5.sp,
                                                color = BentoTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (duration > 0) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                com.example.ui.components.RealtimeTrackProgressBar(
                                                    playbackPositionFlow = playbackPositionFlow,
                                                    isActive = isCurrentActive,
                                                    durationMs = duration,
                                                    showTimestamps = isCurrentActive,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                if (playbackState.currentSong?.id == continueSong.id) {
                                                    onTogglePlayPause()
                                                } else {
                                                    onSongClick(continueSong)
                                                }
                                            },
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(BentoPrimary)
                                        ) {
                                            Icon(
                                                imageVector = if (playbackState.currentSong?.id == continueSong.id && playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = BentoOnPrimary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(thickness = 0.5.dp, color = BentoBorder.copy(alpha = 0.4f))
                                }
                            }
                            else -> {
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
                                            modifier = Modifier.size(50.dp),
                                            cornerRadius = 10
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

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
                }
            }
            "quick_actions" -> {
                item(key = "section_quick_actions") {
                    val context = LocalContext.current
                    HomeFeedStyledHeader(
                        title = "Quick Actions",
                        studioCode = "CH // LAUNCH MATRIX",
                        feedStyle = feedStyle
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    when (feedStyle) {
                        HomeFeedVisualStyle.HERO_SPOTLIGHT -> {
                            // Sculpted Capsule Spotlight Action Strip
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(
                                    onClick = {
                                        if (allSongs.isNotEmpty()) {
                                            onQuickShuffle()
                                            android.widget.Toast.makeText(context, "Shuffling all ${allSongs.size} tracks", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "No songs in library to shuffle", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(
                                                        BentoPrimary.copy(alpha = 0.24f),
                                                        BentoCardDarkPurple.copy(alpha = 0.85f)
                                                    )
                                                )
                                            )
                                            .padding(horizontal = 18.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(BentoPrimary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle All", tint = BentoOnPrimary, modifier = Modifier.size(19.dp))
                                            }
                                            Column {
                                                Text("Shuffle Entire Catalog", fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, color = BentoTextPrimary)
                                                Text("${allSongs.size} tracks ready for instant mix", fontSize = 11.5.sp, color = BentoTextSecondary)
                                            }
                                        }
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(22.dp))
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SpotlightActionCapsule(
                                        icon = Icons.Default.LibraryMusic,
                                        label = "Library",
                                        accent = BentoPrimary,
                                        onClick = onOpenLibrary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SpotlightActionCapsule(
                                        icon = Icons.Default.QueueMusic,
                                        label = "Playlists",
                                        accent = BentoPrimary,
                                        onClick = onOpenPlaylists,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SpotlightActionCapsule(
                                        icon = Icons.Default.Favorite,
                                        label = "Favorites",
                                        accent = BentoAccentPinkText,
                                        onClick = onOpenFavorites,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        HomeFeedVisualStyle.MINIMAL_STREAM -> {
                            // Ultra-clean borderless horizontal pill stream
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                item(key = "min_qa_shuffle") {
                                    Surface(
                                        onClick = {
                                            if (allSongs.isNotEmpty()) {
                                                onQuickShuffle()
                                                android.widget.Toast.makeText(context, "Shuffling all ${allSongs.size} tracks", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        color = BentoPrimary
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Default.Shuffle, contentDescription = null, tint = BentoOnPrimary, modifier = Modifier.size(16.dp))
                                            Text("Shuffle All (${allSongs.size})", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = BentoOnPrimary)
                                        }
                                    }
                                }
                                item(key = "min_qa_lib") {
                                    MinimalActionPill(icon = Icons.Default.LibraryMusic, label = "Library", onClick = onOpenLibrary)
                                }
                                item(key = "min_qa_pl") {
                                    MinimalActionPill(icon = Icons.Default.QueueMusic, label = "Playlists", onClick = onOpenPlaylists)
                                }
                                item(key = "min_qa_fav") {
                                    MinimalActionPill(icon = Icons.Default.Favorite, label = "Favorites", onClick = onOpenFavorites)
                                }
                            }
                        }
                        HomeFeedVisualStyle.STUDIO_DECK -> {
                            // Pro-Audio Sampler Trigger Pads
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StudioTriggerPad(
                                    code = "PAD 01",
                                    label = "SHUFFLE",
                                    sub = "${allSongs.size} TRK",
                                    icon = Icons.Default.Shuffle,
                                    ledColor = Color(0xFF00E676),
                                    onClick = {
                                        if (allSongs.isNotEmpty()) {
                                            onQuickShuffle()
                                            android.widget.Toast.makeText(context, "Shuffling all ${allSongs.size} tracks", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                StudioTriggerPad(
                                    code = "PAD 02",
                                    label = "CATALOG",
                                    sub = "LIBRARY",
                                    icon = Icons.Default.LibraryMusic,
                                    ledColor = BentoPrimary,
                                    onClick = onOpenLibrary,
                                    modifier = Modifier.weight(1f)
                                )
                                StudioTriggerPad(
                                    code = "PAD 03",
                                    label = "CRATES",
                                    sub = "MIXES",
                                    icon = Icons.Default.QueueMusic,
                                    ledColor = BentoPrimary,
                                    onClick = onOpenPlaylists,
                                    modifier = Modifier.weight(1f)
                                )
                                StudioTriggerPad(
                                    code = "PAD 04",
                                    label = "STARRED",
                                    sub = "FAVS",
                                    icon = Icons.Default.Favorite,
                                    ledColor = BentoAccentPinkText,
                                    onClick = onOpenFavorites,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        HomeFeedVisualStyle.BENTO_DEFAULT -> {
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
                                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.20f)),
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
                                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.30f)),
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
                }
            }
            "listening_stats" -> {
                item(key = "section_listening_stats") {
                    HomeFeedStyledHeader(
                        title = "Listening Statistics",
                        studioCode = "CH // AUDIO TELEMETRY",
                        feedStyle = feedStyle,
                        trailingContent = {
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
                    )
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
                                onClick = onOpenStatsDetail,
                                feedStyle = feedStyle
                            )
                        }
                    }
                }
            }
            "most_played" -> {
                item(key = "section_most_played_header") {
                    Column {
                        HomeFeedStyledHeader(
                            title = "Most Played",
                            studioCode = "CH // HEAVY ROTATION",
                            feedStyle = feedStyle
                        )

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
                                    shape = if (feedStyle == HomeFeedVisualStyle.STUDIO_DECK) RoundedCornerShape(6.dp) else RoundedCornerShape(50),
                                    label = {
                                        Text(
                                            text = if (feedStyle == HomeFeedVisualStyle.STUDIO_DECK) filter.label.uppercase() else filter.label,
                                            fontSize = 11.5.sp,
                                            fontFamily = if (feedStyle == HomeFeedVisualStyle.STUDIO_DECK) FontFamily.Monospace else FontFamily.Default,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = BentoPrimary.copy(alpha = 0.16f),
                                        selectedLabelColor = BentoPrimary,
                                        containerColor = if (feedStyle == HomeFeedVisualStyle.MINIMAL_STREAM) Color.Transparent else BentoCardSurface,
                                        labelColor = BentoTextSecondary
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = if (isSelected) BentoPrimary.copy(alpha = 0.45f) else BentoBorder.copy(alpha = 0.28f)
                                    )
                                )
                            }
                        }
                    }
                }

                if (mostPlayedFilteredSongs.isNotEmpty()) {
                    itemsIndexed(mostPlayedFilteredSongs, key = { _, item -> "most_played_${item.id}" }, contentType = { _, _ -> "song_item" }) { index, song ->
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            when (feedStyle) {
                                HomeFeedVisualStyle.HERO_SPOTLIGHT -> {
                                    SpotlightSongCapsuleRow(
                                        rank = index + 1,
                                        song = song,
                                        isPlaying = currentPlayingSongId == song.id,
                                        onClick = { onPlaySongQueue(song, mostPlayedFilteredSongs) },
                                        onFavoriteToggle = { onFavoriteToggle(song) },
                                        onMoreClick = { showMenu = true }
                                    )
                                }
                                HomeFeedVisualStyle.MINIMAL_STREAM -> {
                                    MinimalStreamSongRow(
                                        rank = index + 1,
                                        song = song,
                                        isPlaying = currentPlayingSongId == song.id,
                                        onClick = { onPlaySongQueue(song, mostPlayedFilteredSongs) },
                                        onFavoriteToggle = { onFavoriteToggle(song) },
                                        onMoreClick = { showMenu = true }
                                    )
                                }
                                HomeFeedVisualStyle.STUDIO_DECK -> {
                                    StudioChannelSongStrip(
                                        channelIndex = index + 1,
                                        song = song,
                                        isPlaying = currentPlayingSongId == song.id,
                                        onClick = { onPlaySongQueue(song, mostPlayedFilteredSongs) },
                                        onFavoriteToggle = { onFavoriteToggle(song) },
                                        onMoreClick = { showMenu = true }
                                    )
                                }
                                HomeFeedVisualStyle.BENTO_DEFAULT -> {
                                    SongItem(
                                        song = song,
                                        isPlaying = currentPlayingSongId == song.id,
                                        onClick = { onPlaySongQueue(song, mostPlayedFilteredSongs) },
                                        onFavoriteToggle = { onFavoriteToggle(song) },
                                        onMoreClick = { showMenu = true }
                                    )
                                }
                            }

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
                        HomeFeedStyledHeader(
                            title = "Most Played Playlists",
                            studioCode = "CH // TOP CRATES",
                            feedStyle = feedStyle
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(playlistsToShow, key = { _, item -> "pl_${item.id}" }, contentType = { _, _ -> "playlist_card" }) { index, playlist ->
                                when (feedStyle) {
                                    HomeFeedVisualStyle.HERO_SPOTLIGHT -> {
                                        // Full-bleed portrait spotlight card with bottom gradient overlay
                                        Box(
                                            modifier = Modifier
                                                .width(138.dp)
                                                .height(158.dp)
                                                .clip(RoundedCornerShape(24.dp))
                                                .clickable { onSelectPlaylist(playlist) }
                                        ) {
                                            com.example.ui.components.PlaylistCover(
                                                playlist = playlist,
                                                playlistSongs = emptyList(),
                                                modifier = Modifier.fillMaxSize(),
                                                cornerRadius = 24
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(
                                                        Brush.verticalGradient(
                                                            0.0f to Color.Transparent,
                                                            0.45f to Color.Black.copy(alpha = 0.25f),
                                                            1.0f to Color.Black.copy(alpha = 0.88f)
                                                        )
                                                    )
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(12.dp)
                                            ) {
                                                Text(
                                                    text = playlist.name,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${playlist.songCount} tracks",
                                                    fontSize = 11.sp,
                                                    color = Color.White.copy(alpha = 0.78f)
                                                )
                                            }
                                        }
                                    }
                                    HomeFeedVisualStyle.MINIMAL_STREAM -> {
                                        // Frameless editorial cover + label
                                        Column(
                                            modifier = Modifier
                                                .width(114.dp)
                                                .clickable { onSelectPlaylist(playlist) }
                                        ) {
                                            com.example.ui.components.PlaylistCover(
                                                playlist = playlist,
                                                playlistSongs = emptyList(),
                                                modifier = Modifier
                                                    .size(114.dp)
                                                    .clip(RoundedCornerShape(16.dp)),
                                                cornerRadius = 16
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
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
                                    HomeFeedVisualStyle.STUDIO_DECK -> {
                                        // Studio Cartridge Slot
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = BentoCardSurface,
                                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .width(142.dp)
                                                .clickable { onSelectPlaylist(playlist) }
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "CRATE %02d".format(index + 1),
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoPrimary
                                                    )
                                                    Box(
                                                        modifier = Modifier
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(0xFF00E676))
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                com.example.ui.components.PlaylistCover(
                                                    playlist = playlist,
                                                    playlistSongs = emptyList(),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(82.dp)
                                                        .clip(RoundedCornerShape(8.dp)),
                                                    cornerRadius = 8
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = playlist.name,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BentoTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${playlist.songCount} TRK",
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = BentoTextSecondary
                                                )
                                            }
                                        }
                                    }
                                    HomeFeedVisualStyle.BENTO_DEFAULT -> {
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
                }
            }
            "recently_played" -> {
                if (recentlyPlayed.isNotEmpty()) {
                    item(key = "section_recently_played") {
                        HomeFeedStyledHeader(
                            title = "Recently Played",
                            studioCode = "CH // PLAYBACK LOG",
                            feedStyle = feedStyle,
                            onHeaderClick = onOpenRecentlyPlayed,
                            trailingContent = {
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
                        )
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
                            itemsIndexed(
                                items = uniqueRecent,
                                key = { _, item -> "recent_played_${item.id}" },
                                contentType = { _, _ -> "recent_song" }
                            ) { _, song ->
                                val cornerDp = when (feedStyle) {
                                    HomeFeedVisualStyle.HERO_SPOTLIGHT -> 22
                                    HomeFeedVisualStyle.MINIMAL_STREAM -> 55
                                    HomeFeedVisualStyle.STUDIO_DECK -> 8
                                    HomeFeedVisualStyle.BENTO_DEFAULT -> 12
                                }
                                Column(
                                    modifier = Modifier
                                        .width(112.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onPlaySongQueue(song, uniqueRecent) },
                                    horizontalAlignment = if (feedStyle == HomeFeedVisualStyle.MINIMAL_STREAM) Alignment.CenterHorizontally else Alignment.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(110.dp)
                                            .clip(RoundedCornerShape(cornerDp.dp))
                                    ) {
                                        AlbumArtView(
                                            song = song,
                                            modifier = Modifier.fillMaxSize(),
                                            cornerRadius = cornerDp
                                        )
                                        if (song.playCount > 0) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = Color.Black.copy(alpha = 0.72f),
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(bottom = 6.dp, start = 6.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                        HomeFeedStyledHeader(
                            title = "Recently Added",
                            studioCode = "CH // NEW INGEST",
                            feedStyle = feedStyle
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(recentlyAdded.take(10), key = { _, item -> "recent_added_${item.id}" }, contentType = { _, _ -> "recent_added" }) { _, song ->
                                val cornerDp = when (feedStyle) {
                                    HomeFeedVisualStyle.HERO_SPOTLIGHT -> 22
                                    HomeFeedVisualStyle.MINIMAL_STREAM -> 18
                                    HomeFeedVisualStyle.STUDIO_DECK -> 8
                                    HomeFeedVisualStyle.BENTO_DEFAULT -> 12
                                }
                                Column(
                                    modifier = Modifier
                                        .width(112.dp)
                                        .clickable { onPlaySongQueue(song, recentlyAdded) }
                                ) {
                                    AlbumArtView(
                                        song = song,
                                        modifier = Modifier.size(110.dp),
                                        cornerRadius = cornerDp
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
                        HomeFeedStyledHeader(
                            title = "Top Artists",
                            studioCode = "CH // VOCALISTS & PRODUCERS",
                            feedStyle = feedStyle
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(topArtists, key = { idx, item -> "art_${idx}_${item.name}" }) { _, artist ->
                                when (feedStyle) {
                                    HomeFeedVisualStyle.BENTO_DEFAULT -> {
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
                                    else -> {
                                        // Non-boxy circular Artist Medallion for Hero Spotlight, Minimal Stream, and Studio Deck
                                        Column(
                                            modifier = Modifier.width(104.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(76.dp)
                                                    .clip(CircleShape)
                                                    .border(
                                                        width = if (feedStyle == HomeFeedVisualStyle.HERO_SPOTLIGHT) 2.dp else 1.dp,
                                                        brush = Brush.linearGradient(listOf(BentoPrimary, BentoAccentPinkText)),
                                                        shape = CircleShape
                                                    )
                                                    .padding(4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                com.example.ui.components.ArtistAvatar(
                                                    artistName = artist.name,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = artist.name,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                            Text(
                                                text = "${artist.playCount} plays",
                                                fontSize = 10.5.sp,
                                                color = BentoPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
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
                        HomeFeedStyledHeader(
                            title = "Top Albums",
                            studioCode = "CH // MASTER RELEASES",
                            feedStyle = feedStyle
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(topAlbums, key = { idx, item -> "alb_${idx}_${item.name}" }) { _, album ->
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

                                when (feedStyle) {
                                    HomeFeedVisualStyle.BENTO_DEFAULT -> {
                                        BentoCard(
                                            modifier = Modifier.width(130.dp),
                                            backgroundColor = BentoCardSurface
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                                    HomeFeedVisualStyle.HERO_SPOTLIGHT -> {
                                        // Full-bleed portrait album tile with gradient overlay
                                        Box(
                                            modifier = Modifier
                                                .width(134.dp)
                                                .height(152.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                        ) {
                                            AlbumArtView(
                                                song = dummySongForArt,
                                                modifier = Modifier.fillMaxSize(),
                                                cornerRadius = 22
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(
                                                        Brush.verticalGradient(
                                                            0.0f to Color.Transparent,
                                                            0.45f to Color.Black.copy(alpha = 0.2f),
                                                            1.0f to Color.Black.copy(alpha = 0.88f)
                                                        )
                                                    )
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(10.dp)
                                            ) {
                                                Text(
                                                    text = album.name,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${album.playCount} plays",
                                                    fontSize = 10.5.sp,
                                                    color = BentoPrimary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        // Frameless Album Sleeve
                                        Column(modifier = Modifier.width(112.dp)) {
                                            AlbumArtView(
                                                song = dummySongForArt,
                                                modifier = Modifier.size(112.dp),
                                                cornerRadius = if (feedStyle == HomeFeedVisualStyle.STUDIO_DECK) 8 else 16
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = album.name,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
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
}

@Composable
private fun HomeFeedStyledHeader(
    title: String,
    studioCode: String,
    feedStyle: HomeFeedVisualStyle,
    onHeaderClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val rowModifier = if (onHeaderClick != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onHeaderClick() }
            .padding(vertical = 4.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    when (feedStyle) {
        HomeFeedVisualStyle.HERO_SPOTLIGHT -> {
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Brush.verticalGradient(listOf(BentoPrimary, BentoAccentPinkText)))
                    )
                    Text(
                        text = title,
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoTextPrimary
                    )
                }
                trailingContent?.invoke()
            }
        }
        HomeFeedVisualStyle.MINIMAL_STREAM -> {
            Column(modifier = rowModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.uppercase(),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoPrimary,
                        letterSpacing = 1.4.sp
                    )
                    trailingContent?.invoke()
                }
                HorizontalDivider(thickness = 0.5.dp, color = BentoBorder.copy(alpha = 0.45f))
            }
        }
        HomeFeedVisualStyle.STUDIO_DECK -> {
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                    )
                    Column {
                        Text(
                            text = studioCode,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = title,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                    }
                }
                trailingContent?.invoke()
            }
        }
        HomeFeedVisualStyle.BENTO_DEFAULT -> {
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextPrimary
                )
                trailingContent?.invoke()
            }
        }
    }
}

@Composable
private fun SpotlightActionCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = BentoCardSurface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary, maxLines = 1)
        }
    }
}

@Composable
private fun MinimalActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(15.dp))
            Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = BentoTextPrimary)
        }
    }
}

@Composable
private fun StudioTriggerPad(
    code: String,
    label: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ledColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, ledColor.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = code,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextSecondary
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ledColor)
                )
            }
            Icon(icon, contentDescription = label, tint = ledColor, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                color = BentoTextPrimary,
                maxLines = 1
            )
            Text(
                text = sub,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = BentoTextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SpotlightSongCapsuleRow(
    rank: Int,
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onMoreClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (isPlaying) BentoPrimary.copy(alpha = 0.14f) else BentoCardSurface.copy(alpha = 0.78f),
        border = BorderStroke(
            1.dp,
            if (isPlaying) BentoPrimary.copy(alpha = 0.55f) else BentoBorder.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (rank <= 3) BentoPrimary.copy(alpha = 0.22f) else BentoCardDarkPurple),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "%02d".format(rank),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (rank <= 3) BentoPrimary else BentoTextSecondary
                )
            }
            AlbumArtView(
                song = song,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
                cornerRadius = 50
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.displayTitle,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPlaying) BentoPrimary else BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.displayArtist} • ${song.playCount} plays",
                    fontSize = 11.5.sp,
                    color = BentoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onMoreClick, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = BentoTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun MinimalStreamSongRow(
    rank: Int,
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onMoreClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "%02d".format(rank),
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                color = if (isPlaying) BentoPrimary else BentoTextSecondary,
                modifier = Modifier.width(22.dp)
            )
            AlbumArtView(
                song = song,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp)),
                cornerRadius = 10
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.displayTitle,
                    fontSize = 14.5.sp,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isPlaying) BentoPrimary else BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.displayArtist,
                    fontSize = 12.sp,
                    color = BentoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (song.playCount > 0) {
                Text(
                    text = "${song.playCount}×",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoPrimary
                )
            }
            IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary,
                    modifier = Modifier.size(17.dp)
                )
            }
            IconButton(onClick = onMoreClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = BentoTextSecondary,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = BentoBorder.copy(alpha = 0.35f))
    }
}

@Composable
private fun StudioChannelSongStrip(
    channelIndex: Int,
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BentoCardSurface.copy(alpha = 0.88f))
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left LED Signal Strip
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(54.dp)
                .background(if (isPlaying) Color(0xFF00E676) else BentoPrimary.copy(alpha = 0.55f))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "CH%02d".format(channelIndex),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (isPlaying) Color(0xFF00E676) else BentoPrimary
        )
        Spacer(modifier = Modifier.width(10.dp))
        AlbumArtView(
            song = song,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp)),
            cornerRadius = 6
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayTitle,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) Color(0xFF00E676) else BentoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${song.displayArtist} • ${song.playCount} CUES",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = BentoTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary,
                modifier = Modifier.size(17.dp)
            )
        }
        IconButton(onClick = onMoreClick, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = BentoTextSecondary,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

private val cachedPeriodStats = java.util.concurrent.ConcurrentHashMap<StatsPeriod, Triple<Long, Int, Int>>()

@Composable
fun HomeStatsPeriodCard(
    period: StatsPeriod,
    getDurationPlayedBetween: (Long, Long) -> Flow<Long>,
    getTrackCountBetween: (Long, Long) -> Flow<Int>,
    getUniqueTrackCountBetween: (Long, Long) -> Flow<Int>,
    onClick: () -> Unit = {},
    feedStyle: HomeFeedVisualStyle = HomeFeedVisualStyle.BENTO_DEFAULT
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

    val cached = remember(period) { cachedPeriodStats[period] }

    val durationFlow = remember(periodStartMs, periodEndMs) { getDurationPlayedBetween(periodStartMs, periodEndMs) }
    val trackCountFlow = remember(periodStartMs, periodEndMs) { getTrackCountBetween(periodStartMs, periodEndMs) }
    val uniqueCountFlow = remember(periodStartMs, periodEndMs) { getUniqueTrackCountBetween(periodStartMs, periodEndMs) }

    val durationMs by durationFlow.collectAsStateWithLifecycle(initialValue = cached?.first ?: -1L)
    val trackCount by trackCountFlow.collectAsStateWithLifecycle(initialValue = cached?.second ?: -1)
    val uniqueCount by uniqueCountFlow.collectAsStateWithLifecycle(initialValue = cached?.third ?: -1)

    LaunchedEffect(period, durationMs, trackCount, uniqueCount) {
        if (durationMs >= 0L && trackCount >= 0 && uniqueCount >= 0) {
            cachedPeriodStats[period] = Triple(durationMs, trackCount, uniqueCount)
        }
    }

    val formattedTime = remember(durationMs) {
        if (durationMs < 0L) return@remember "0m"
        val totalMins = durationMs / 1000 / 60
        val hrs = totalMins / 60
        val mins = totalMins % 60
        if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
    }

    val subText = if (trackCount < 0 || uniqueCount < 0) "0 plays • 0 tracks" else "$trackCount plays • $uniqueCount tracks"

    when (feedStyle) {
        HomeFeedVisualStyle.HERO_SPOTLIGHT -> {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(24.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.38f)),
                modifier = Modifier.width(154.dp)
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    BentoPrimary.copy(alpha = 0.20f),
                                    BentoCardSurface
                                )
                            )
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = period.label.uppercase(), fontSize = 10.sp, color = BentoPrimary, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                    Text(text = formattedTime, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = BentoTextPrimary)
                    Text(text = subText, fontSize = 11.sp, color = BentoTextSecondary, maxLines = 1)
                }
            }
        }
        HomeFeedVisualStyle.MINIMAL_STREAM -> {
            Row(
                modifier = Modifier
                    .width(142.dp)
                    .clickable(onClick = onClick)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(44.dp)
                        .background(BentoPrimary)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = period.label.uppercase(), fontSize = 10.sp, color = BentoTextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(text = formattedTime, fontSize = 21.sp, fontWeight = FontWeight.Light, color = BentoTextPrimary)
                    Text(text = subText, fontSize = 10.5.sp, color = BentoPrimary)
                }
            }
        }
        HomeFeedVisualStyle.STUDIO_DECK -> {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.4f)),
                modifier = Modifier.width(152.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = period.label.uppercase(), fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = BentoPrimary, fontWeight = FontWeight.Bold)
                        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF00E676)))
                    }
                    Text(text = formattedTime, fontSize = 19.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, color = BentoTextPrimary)
                    Text(text = subText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = BentoTextSecondary, maxLines = 1)
                }
            }
        }
        HomeFeedVisualStyle.BENTO_DEFAULT -> {
            BentoCard(
                modifier = Modifier
                    .width(146.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onClick),
                backgroundColor = BentoCardSurface
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = period.label.uppercase(), fontSize = 10.5.sp, color = BentoPrimary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    Text(text = formattedTime, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                    Text(text = subText, fontSize = 11.sp, color = BentoTextSecondary)
                }
            }
        }
    }
}
