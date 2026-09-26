package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favoriteSongs: List<Song>,
    playbackState: PlaybackState,
    onSongClick: (Song) -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onShuffleFavorites: () -> Unit,
    onPlayAll: () -> Unit = { if (favoriteSongs.isNotEmpty()) onSongClick(favoriteSongs.first()) },
    onClearAllFavorites: (() -> Unit)? = null,
    onExploreLibrary: (() -> Unit)? = null,
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    var sortOption by remember { mutableStateOf("TITLE") }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outline
    val pinkAccent = BentoAccentPinkText

    val m3uExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val m3uContent = M3uParser.exportM3u("Favorites", favoriteSongs)
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(m3uContent.toByteArray())
                }
                Toast.makeText(context, "Exported ${favoriteSongs.size} favorites as M3U", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Filter & Sort Logic
    val filteredSongs = remember(favoriteSongs, searchQuery, selectedFilter, sortOption) {
        var list = favoriteSongs

        // Search Filter
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
            }
        }

        // Quick Category Filter
        list = when (selectedFilter) {
            "RECENT" -> list.sortedByDescending { it.dateAdded }
            "POPULAR" -> list.sortedByDescending { it.playCount }
            "LONGEST" -> list.sortedByDescending { it.durationMs }
            else -> list
        }

        // Explicit Sort
        when (sortOption) {
            "TITLE" -> if (selectedFilter == "ALL") list.sortedBy { it.title.lowercase() } else list
            "ARTIST" -> list.sortedBy { it.artist.lowercase() }
            "DATE" -> list.sortedByDescending { it.dateAdded }
            "DURATION" -> list.sortedByDescending { it.durationMs }
            else -> list
        }
    }

    // Statistics calculations
    val totalDurationMs = remember(favoriteSongs) { favoriteSongs.sumOf { it.durationMs } }
    val formattedTotalDuration = remember(totalDurationMs) {
        val totalSec = totalDurationMs / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
    }
    val uniqueArtistsCount = remember(favoriteSongs) { favoriteSongs.map { it.artist.trim().lowercase() }.distinct().size }

    // Heart Pulse Animation
    val infiniteTransition = rememberInfiniteTransition(label = "HeartPulse")
    val heartScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HeartScale"
    )

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = "Clear All Favorites?",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove all songs from your favorites list? This will not delete audio files from your device.",
                    color = BentoTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        onClearAllFavorites?.invoke()
                        Toast.makeText(context, "Favorites cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("favorites_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        val listState = rememberLazyListState()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Hero Header Banner
            item(key = "favorites_hero_header") {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                listOf(
                                    primaryColor.copy(alpha = 0.5f),
                                    pinkAccent.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        surfaceVariantColor.copy(alpha = 0.95f),
                                        surfaceColor.copy(alpha = 0.98f)
                                    )
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Top Row: Title, Heart Icon, and Export/Clear Actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .scale(if (favoriteSongs.isNotEmpty()) heartScale else 1f)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.radialGradient(
                                                    listOf(
                                                        pinkAccent.copy(alpha = 0.35f),
                                                        surfaceVariantColor
                                                    )
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Favorite,
                                            contentDescription = "Favorite Heart",
                                            tint = pinkAccent,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = "Favorite Tracks",
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = BentoTextPrimary,
                                            letterSpacing = (-0.5).sp
                                        )
                                        Text(
                                            text = "Your curated personal collection",
                                            fontSize = 13.sp,
                                            color = BentoTextSecondary
                                        )
                                    }
                                }

                                if (favoriteSongs.isNotEmpty()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { m3uExportLauncher.launch("Favorites.m3u") },
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(surfaceVariantColor)
                                                .testTag("export_m3u_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FileDownload,
                                                contentDescription = "Export Favorites as M3U",
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        if (onClearAllFavorites != null) {
                                            IconButton(
                                                onClick = { showClearConfirmDialog = true },
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(surfaceVariantColor)
                                                    .testTag("clear_favorites_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.DeleteSweep,
                                                    contentDescription = "Clear All Favorites",
                                                    tint = BentoTextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Statistics Row (Track count, Duration, Artists)
                            if (favoriteSongs.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatPillBadge(
                                        icon = Icons.Filled.MusicNote,
                                        text = "${favoriteSongs.size} Tracks",
                                        tint = primaryColor,
                                        backgroundColor = surfaceVariantColor
                                    )
                                    StatPillBadge(
                                        icon = Icons.Filled.AccessTime,
                                        text = formattedTotalDuration,
                                        tint = primaryColor,
                                        backgroundColor = surfaceVariantColor
                                    )
                                    if (uniqueArtistsCount > 0) {
                                        StatPillBadge(
                                            icon = Icons.Filled.Person,
                                            text = "$uniqueArtistsCount Artists",
                                            tint = pinkAccent,
                                            backgroundColor = surfaceVariantColor
                                        )
                                    }
                                }

                                // Quick Play & Shuffle Action Buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = onPlayAll,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BentoPrimary,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .testTag("play_all_favorites_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Play All",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }

                                    Button(
                                        onClick = onShuffleFavorites,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BentoAccentPinkBg,
                                            contentColor = BentoAccentPinkText
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .testTag("shuffle_favorites_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Shuffle,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Shuffle",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Search & Filter Bar (Shown when favorites are present)
            if (favoriteSongs.isNotEmpty()) {
                item(key = "favorites_search_filter") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Search TextField
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    "Search inside favorites...",
                                    color = BentoTextSecondary,
                                    fontSize = 14.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = BentoTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear",
                                            tint = BentoTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = BentoCardSurface,
                                unfocusedContainerColor = BentoCardSurface,
                                focusedBorderColor = BentoPrimary,
                                unfocusedBorderColor = outlineColor.copy(alpha = 0.4f),
                                focusedTextColor = BentoTextPrimary,
                                unfocusedTextColor = BentoTextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("favorites_search_field")
                        )

                        // Filter and Sort Chips Row
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val filters = listOf(
                                "ALL" to "All",
                                "RECENT" to "Recently Added",
                                "POPULAR" to "Most Played",
                                "LONGEST" to "Longest"
                            )

                            items(filters, key = { it.first }, contentType = { "filter_chip" }) { (key, label) ->
                                val isSelected = selectedFilter == key
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedFilter = key },
                                    label = {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = BentoPrimary.copy(alpha = 0.2f),
                                        selectedLabelColor = BentoPrimary,
                                        containerColor = BentoCardSurface,
                                        labelColor = BentoTextSecondary
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = if (isSelected) primaryColor else outlineColor.copy(alpha = 0.3f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Content: Empty State vs Filtered List
            if (favoriteSongs.isEmpty()) {
                item(key = "favorites_empty_state") {
                    BentoCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        backgroundColor = BentoCardSurface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(surfaceVariantColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FavoriteBorder,
                                    contentDescription = null,
                                    tint = pinkAccent,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            Text(
                                text = "No Favorites Yet",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )

                            Text(
                                text = "Tap the heart icon on any track while playing or browsing to add it to your favorites. Favorites stay saved across app restarts.",
                                fontSize = 13.sp,
                                color = BentoTextSecondary,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            if (onExploreLibrary != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = onExploreLibrary,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BentoPrimary,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .height(46.dp)
                                        .testTag("explore_library_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.LibraryMusic,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Explore Library", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else if (filteredSongs.isEmpty()) {
                item(key = "favorites_no_search_results") {
                    BentoCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        backgroundColor = BentoCardSurface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SearchOff,
                                contentDescription = null,
                                tint = BentoTextSecondary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "No matching favorites found",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "Try searching for a different song title or artist.",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = filteredSongs,
                    key = { idx, song -> "${song.id}_$idx" },
                    contentType = { _, _ -> "favorite_song_item" }
                ) { index, song ->
                    Box {
                        SongItem(
                            song = song,
                            isPlaying = playbackState.currentSong?.id == song.id,
                            onClick = { onSongClick(song) },
                            onFavoriteToggle = { onFavoriteToggle(song) },
                            onMoreClick = { selectedSongForMenu = song }
                        )

                        if (selectedSongForMenu?.id == song.id) {
                            SongActionMenu(
                                expanded = true,
                                song = song,
                                onDismissRequest = { selectedSongForMenu = null },
                                onPlayNow = { onSongClick(song) },
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
            }
        }

        if (filteredSongs.size > 8) {
            FastScrollbar(
                listState = listState,
                totalItems = filteredSongs.size,
                bottomPadding = 120.dp,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun StatPillBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = BentoTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
