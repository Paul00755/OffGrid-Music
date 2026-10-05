package com.example.ui.screens

import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.scanner.M3uParser
import com.example.service.PlaybackState
import com.example.ui.components.BentoCard
import com.example.ui.components.FastScrollbar
import com.example.ui.components.SongActionMenu
import com.example.ui.components.SongItem
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

enum class RecentlyPlayedSort(val displayName: String) {
    RECENTLY_PLAYED("Recently Played"),
    MOST_PLAYED("Most Played")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyPlayedScreen(
    recentlyPlayedSongs: List<Song>,
    playbackState: PlaybackState,
    onSongClick: (Song) -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayAll: () -> Unit = { if (recentlyPlayedSongs.isNotEmpty()) onSongClick(recentlyPlayedSongs.first()) },
    onClearHistory: (() -> Unit)? = null,
    onBackClick: () -> Unit,
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    dailyStats: List<com.example.data.model.DailyListeningStat> = emptyList(),
    onOpenStatsDetail: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var currentSort by remember { mutableStateOf(RecentlyPlayedSort.RECENTLY_PLAYED) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Unique list capped at 100 songs
    val uniqueLimitedSongs = remember(recentlyPlayedSongs) {
        val seenKeys = mutableSetOf<String>()
        recentlyPlayedSongs.filter { song ->
            val key = if (song.id > 0) "id_${song.id}" else "path_${song.path}"
            seenKeys.add(key)
        }.take(100)
    }

    // Apply sorting
    val sortedSongs = remember(uniqueLimitedSongs, currentSort) {
        when (currentSort) {
            RecentlyPlayedSort.RECENTLY_PLAYED -> uniqueLimitedSongs.sortedByDescending { it.lastPlayed }
            RecentlyPlayedSort.MOST_PLAYED -> uniqueLimitedSongs.sortedWith(
                compareByDescending<Song> { it.playCount }.thenByDescending { it.lastPlayed }
            )
        }
    }

    val m3uExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Ensure duplicate entries in recently played are removed when exporting
                val distinctSongs = sortedSongs.take(100).distinctBy { if (it.id > 0) it.id.toString() else it.path }
                val m3uContent = M3uParser.exportM3u("Recently Played", distinctSongs)
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(m3uContent.toByteArray())
                }
                Toast.makeText(context, "Exported ${distinctSongs.size} tracks as M3U", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Filter list based on search
    val filteredSongs = remember(sortedSongs, searchQuery) {
        if (searchQuery.isBlank()) {
            sortedSongs
        } else {
            val q = searchQuery.trim().lowercase()
            sortedSongs.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
            }
        }
    }

    // Total listening statistics
    val totalDurationMs = remember(uniqueLimitedSongs) {
        uniqueLimitedSongs.sumOf { it.durationMs }
    }
    val totalDurationFormatted = remember(totalDurationMs) {
        val totalSec = totalDurationMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBackground),
        containerColor = BentoBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Recently Played",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            if (recentlyPlayedSongs.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = BentoPrimary.copy(alpha = 0.2f),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text(
                                        text = "${recentlyPlayedSongs.size}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Last ${recentlyPlayedSongs.size.coerceAtMost(100)} played tracks",
                            fontSize = 12.sp,
                            color = BentoTextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = BentoTextPrimary
                        )
                    }
                },
                actions = {
                    if (recentlyPlayedSongs.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                m3uExportLauncher.launch("RecentlyPlayed_$timestamp.m3u")
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "Export M3U Playlist",
                                tint = BentoTextSecondary
                            )
                        }
                        if (onClearHistory != null) {
                            IconButton(onClick = { showClearConfirmDialog = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = "Clear History",
                                    tint = BentoAccentPinkText
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BentoBackground,
                    titleContentColor = BentoTextPrimary
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (recentlyPlayedSongs.isEmpty()) {
                // Empty State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = BentoCardDarkPurple,
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "No History Yet",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Songs you listen to will automatically appear here.",
                        fontSize = 14.sp,
                        color = BentoTextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onBackClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Go to Library", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 120.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 7-Day Visual Summary Bar Chart Section
                    if (dailyStats.isNotEmpty()) {
                        item(key = "visual_summary_chart") {
                            BentoCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 14.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.BarChart,
                                                contentDescription = null,
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "7-Day Listening Activity",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoTextPrimary
                                            )
                                        }

                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { onOpenStatsDetail() }
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "view in detail →",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoPrimary
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    com.example.ui.components.DailyListeningBarChart(
                                        dailyStats = dailyStats,
                                        chartHeight = 140.dp,
                                        showSummaryHeader = false,
                                        accentColor = BentoPrimary
                                    )
                                }
                            }
                        }
                    }

                    // Quick Action & Stat Header
                    item(key = "header_summary") {
                        BentoCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Playback History",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary
                                        )
                                        Text(
                                            text = "${recentlyPlayedSongs.size} tracks • $totalDurationFormatted total",
                                            fontSize = 12.sp,
                                            color = BentoTextSecondary
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(
                                            onClick = onShuffleAll,
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = BentoCardDarkPurple,
                                                contentColor = BentoPrimary
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Shuffle", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        }

                                        Button(
                                            onClick = onPlayAll,
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = BentoPrimary,
                                                contentColor = BentoOnPrimary
                                            ),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play All", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Play All", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Search Field
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Search history...", color = BentoTextSecondary, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = BentoTextSecondary, modifier = Modifier.size(18.dp))
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(imageVector = Icons.Default.Close, contentDescription = "Clear", tint = BentoTextSecondary, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = BentoCardSurface,
                                        unfocusedContainerColor = BentoCardSurface,
                                        focusedBorderColor = BentoPrimary,
                                        unfocusedBorderColor = Color(0x33FFFFFF),
                                        focusedTextColor = BentoTextPrimary,
                                        unfocusedTextColor = BentoTextPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Sort Options Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Sort:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BentoTextSecondary
                                    )

                                    FilterChip(
                                        selected = currentSort == RecentlyPlayedSort.RECENTLY_PLAYED,
                                        onClick = { currentSort = RecentlyPlayedSort.RECENTLY_PLAYED },
                                        label = {
                                            Text(
                                                text = "Recently Played",
                                                fontSize = 12.sp,
                                                fontWeight = if (currentSort == RecentlyPlayedSort.RECENTLY_PLAYED) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.AccessTime,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BentoPrimary,
                                            selectedLabelColor = BentoOnPrimary,
                                            selectedLeadingIconColor = BentoOnPrimary,
                                            containerColor = BentoCardDarkPurple,
                                            labelColor = BentoTextSecondary,
                                            iconColor = BentoTextSecondary
                                        ),
                                        border = null
                                    )

                                    FilterChip(
                                        selected = currentSort == RecentlyPlayedSort.MOST_PLAYED,
                                        onClick = { currentSort = RecentlyPlayedSort.MOST_PLAYED },
                                        label = {
                                            Text(
                                                text = "Most Played",
                                                fontSize = 12.sp,
                                                fontWeight = if (currentSort == RecentlyPlayedSort.MOST_PLAYED) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.TrendingUp,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BentoPrimary,
                                            selectedLabelColor = BentoOnPrimary,
                                            selectedLeadingIconColor = BentoOnPrimary,
                                            containerColor = BentoCardDarkPurple,
                                            labelColor = BentoTextSecondary,
                                            iconColor = BentoTextSecondary
                                        ),
                                        border = null
                                    )
                                }
                            }
                        }
                    }

                    // Song List
                    if (filteredSongs.isEmpty() && searchQuery.isNotEmpty()) {
                        item(key = "no_search_results") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No songs matching \"$searchQuery\"",
                                    color = BentoTextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = filteredSongs,
                            key = { index, song -> "recent_unique_${song.id}_$index" }
                        ) { index, song ->
                            val isPlaying = playbackState.currentSong?.id == song.id
                            val relativeTime = remember(song.lastPlayed) {
                                if (song.lastPlayed > 0L) {
                                    val now = System.currentTimeMillis()
                                    val diff = now - song.lastPlayed
                                    when {
                                        diff < 60_000L -> "Just now"
                                        diff < 3600_000L -> "${diff / 60_000L}m ago"
                                        diff < 86400_000L -> "${diff / 3600_000L}h ago"
                                        diff < 172800_000L -> "Yesterday"
                                        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(song.lastPlayed))
                                    }
                                } else ""
                            }

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (currentSort == RecentlyPlayedSort.MOST_PLAYED) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (index < 3) BentoPrimary.copy(alpha = 0.2f) else BentoCardDarkPurple,
                                                modifier = Modifier.padding(end = 6.dp)
                                            ) {
                                                Text(
                                                    text = "#${index + 1}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (index < 3) BentoPrimary else BentoTextSecondary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                            if (relativeTime.isNotBlank()) {
                                                Text(
                                                    text = "Last: $relativeTime",
                                                    fontSize = 11.sp,
                                                    color = BentoTextSecondary.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                    } else {
                                        if (relativeTime.isNotBlank()) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.AccessTime,
                                                    contentDescription = null,
                                                    tint = BentoTextSecondary.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = relativeTime,
                                                    fontSize = 11.sp,
                                                    color = BentoTextSecondary.copy(alpha = 0.8f),
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.width(1.dp))
                                        }
                                    }

                                    if (song.playCount > 0) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = BentoPrimary.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = "${song.playCount} play${if (song.playCount > 1) "s" else ""}",
                                                fontSize = 11.sp,
                                                color = BentoPrimary,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                SongItem(
                                    song = song,
                                    isPlaying = isPlaying,
                                    onClick = { onSongClick(song) },
                                    onFavoriteToggle = { onFavoriteToggle(song) },
                                    onMoreClick = { selectedSongForMenu = song }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }

                // Smooth Fast Scrollbar
                FastScrollbar(
                    listState = listState,
                    totalItems = filteredSongs.size,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }

    // Action Menu Modal Sheet
    selectedSongForMenu?.let { song ->
        SongActionMenu(
            expanded = true,
            song = song,
            onDismissRequest = { selectedSongForMenu = null },
            onPlayNext = { onPlayNext(song) },
            onAddToQueue = { onAddToQueue(song) },
            onAddToPlaylist = { onAddToPlaylist(song) },
            onFavoriteToggle = { onFavoriteToggle(song) },
            onDeleteSong = { onDeleteSong(song) },
            onEditTags = onEditTags?.let { { it(song) } }
        )
    }

    // Clear History Confirmation Dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text("Clear History?", color = BentoTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Are you sure you want to clear your recently played songs history?",
                    color = BentoTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        onClearHistory?.invoke()
                        Toast.makeText(context, "Playback history cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoAccentPinkText)
                ) {
                    Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", color = BentoTextPrimary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
