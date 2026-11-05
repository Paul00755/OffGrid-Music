package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.scanner.M3uParser
import com.example.service.PlaybackState
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.AlbumArtView
import com.example.ui.components.BentoCard
import com.example.ui.components.FastScrollbar
import com.example.ui.components.SongActionMenu
import com.example.ui.components.SongItem
import com.example.ui.components.rememberReorderableLazyListState
import com.example.ui.components.reorderableDragHandle
import com.example.ui.theme.*
import com.example.util.HapticUtils
import java.util.Locale

data class PlaylistSongEntry(val stableKey: String, val song: Song)

fun buildPlaylistEntries(songs: List<Song>, playlistId: Long): List<PlaylistSongEntry> {
    val occurrences = mutableMapOf<Long, Int>()
    return songs.map { song ->
        val count = occurrences.getOrDefault(song.id, 0) + 1
        occurrences[song.id] = count
        PlaylistSongEntry(
            stableKey = "pl_${playlistId}_${song.id}_${count}_${song.path.hashCode()}",
            song = song
        )
    }
}

enum class PlaylistSortOrder(val displayName: String, val icon: ImageVector) {
    CUSTOM("Custom", Icons.Default.Reorder),
    LATEST("Latest", Icons.Default.Schedule),
    TITLE("Title", Icons.Default.SortByAlpha),
    ARTIST("Artist", Icons.Default.Person),
    ALBUM("Album", Icons.Default.Album),
    DURATION("Duration", Icons.Default.Timer)
}

@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    playlistSongs: List<Song>,
    allPlaylists: List<Playlist>,
    playbackState: PlaybackState,
    onBackClick: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onRemoveSongFromPlaylist: (Long, Long) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRemoveDuplicates: (() -> Unit)? = null,
    onRenamePlaylist: (Long, String) -> Unit = { _, _ -> },
    onAddSongToPlaylist: (Long, Long) -> Unit,
    onCreateNewPlaylist: (String) -> Unit,
    onUpdatePlaylistArtwork: (Long, String?) -> Unit = { _, _ -> },
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onShuffleAll: ((List<Song>) -> Unit)? = null,
    onPlayAll: ((List<Song>) -> Unit)? = null,
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    onReorderSongs: ((List<Song>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(playlist.name) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var songForAddToPlaylist by remember { mutableStateOf<Song?>(null) }

    var selectedSortOrder by remember(playlist.id) { mutableStateOf(PlaylistSortOrder.CUSTOM) }
    var isAscending by remember(playlist.id) { mutableStateOf(true) }
    var lastCommittedOrderIds by remember(playlist.id) { mutableStateOf<List<Long>?>(null) }
    val currentPlayingSongId = remember(playbackState.currentSong?.id) { playbackState.currentSong?.id }

    // Maintain a mutable list of entries for live drag-and-drop reordering with position-independent stable composite keys
    val initialEntries = remember(playlist.id) {
        buildPlaylistEntries(playlistSongs, playlist.id)
    }
    val localEntries = remember(playlist.id) {
        mutableStateListOf<PlaylistSongEntry>().apply { addAll(initialEntries) }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 66.dp.toPx() }
    val headerCount = if (playlistSongs.isNotEmpty()) 3 else 2

    val reorderableState = rememberReorderableLazyListState(
        listState = listState,
        itemHeightPx = itemHeightPx,
        headerItemCount = headerCount,
        onMove = { from, to ->
            if (from != to && from in localEntries.indices && to in localEntries.indices) {
                try {
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                        if (kotlin.math.abs(from - to) == 1) {
                            val tmp = localEntries[from]
                            localEntries[from] = localEntries[to]
                            localEntries[to] = tmp
                        } else {
                            val item = localEntries.removeAt(from)
                            val safeTo = to.coerceIn(0, localEntries.size)
                            localEntries.add(safeTo, item)
                        }
                    }
                    HapticUtils.performTickHaptic(context)
                } catch (e: Exception) {
                    android.util.Log.e("PlaylistDetailScreen", "Error in onMove: from=$from to=$to", e)
                }
            }
        },
        onDragEnd = { _, _ ->
            try {
                val updatedSongs = localEntries.map { it.song }
                lastCommittedOrderIds = updatedSongs.map { it.id }
                onReorderSongs?.invoke(updatedSongs)
            } catch (e: Exception) {
                android.util.Log.e("PlaylistDetailScreen", "Error in onDragEnd", e)
            }
        }
    )

    DisposableEffect(playlist.id) {
        onDispose {
            if (selectedSortOrder == PlaylistSortOrder.CUSTOM) {
                val updatedSongs = localEntries.map { it.song }
                if (updatedSongs.isNotEmpty()) {
                    onReorderSongs?.invoke(updatedSongs)
                }
            }
        }
    }

    androidx.activity.compose.BackHandler {
        if (selectedSortOrder == PlaylistSortOrder.CUSTOM) {
            val updatedSongs = localEntries.map { it.song }
            if (updatedSongs.isNotEmpty()) {
                onReorderSongs?.invoke(updatedSongs)
            }
        }
        onBackClick()
    }

    LaunchedEffect(playlistSongs, reorderableState.isDragging, reorderableState.isSettling) {
        if (!reorderableState.isDragging && !reorderableState.isSettling) {
            val incomingIds = playlistSongs.map { it.id }
            val currentLocalIds = localEntries.map { it.song.id }
            val committedIds = lastCommittedOrderIds

            if (committedIds != null) {
                if (incomingIds == committedIds) {
                    lastCommittedOrderIds = null
                } else if (incomingIds.size == currentLocalIds.size && incomingIds.toSet() == currentLocalIds.toSet()) {
                    val updatedSongMap = playlistSongs.associateBy { it.id }
                    for (i in localEntries.indices) {
                        val currentEntry = localEntries[i]
                        val updated = updatedSongMap[currentEntry.song.id]
                        if (updated != null && updated != currentEntry.song) {
                            localEntries[i] = currentEntry.copy(song = updated)
                        }
                    }
                    return@LaunchedEffect
                } else {
                    lastCommittedOrderIds = null
                }
            }

            if (currentLocalIds != incomingIds) {
                localEntries.clear()
                localEntries.addAll(buildPlaylistEntries(playlistSongs, playlist.id))
            } else {
                val updatedSongMap = playlistSongs.associateBy { it.id }
                for (i in localEntries.indices) {
                    val currentEntry = localEntries[i]
                    val updated = updatedSongMap[currentEntry.song.id]
                    if (updated != null && updated != currentEntry.song) {
                        localEntries[i] = currentEntry.copy(song = updated)
                    }
                }
            }
        }
    }

    val displayEntries by remember(selectedSortOrder, isAscending) {
        derivedStateOf {
            val snapshot = localEntries.toList()
            val sorted = when (selectedSortOrder) {
                PlaylistSortOrder.CUSTOM -> snapshot
                PlaylistSortOrder.LATEST -> snapshot.sortedByDescending { it.song.dateAdded }
                PlaylistSortOrder.TITLE -> snapshot.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.song.displayTitle })
                PlaylistSortOrder.ARTIST -> snapshot.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.song.displayArtist })
                PlaylistSortOrder.ALBUM -> snapshot.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.song.displayAlbum })
                PlaylistSortOrder.DURATION -> snapshot.sortedBy { it.song.durationMs }
            }
            if (!isAscending && selectedSortOrder != PlaylistSortOrder.CUSTOM) {
                sorted.reversed()
            } else {
                sorted
            }
        }
    }

    val displaySongs by remember {
        derivedStateOf { displayEntries.map { it.song } }
    }

    // Proactively pre-decode thumbnails ahead of the scroll viewport for 120fps scrolling
    // Keyed on playlist.id, playlistSongs.size, and selectedSortOrder so drag-reordering swaps
    // never cancel and restart this coroutine mid-drag.
    LaunchedEffect(playlist.id, playlistSongs.size, selectedSortOrder) {
        if (playlistSongs.isNotEmpty()) {
            snapshotFlow { (listState.firstVisibleItemIndex - 3).coerceAtLeast(0) }
                .collect { firstSongIdx ->
                    val currentList = if (selectedSortOrder == PlaylistSortOrder.CUSTOM) {
                        localEntries.map { it.song }
                    } else {
                        displaySongs
                    }
                    if (currentList.isNotEmpty()) {
                        com.example.ui.components.ArtworkResolutionCache.preloadWindow(
                            context = context,
                            songs = currentList,
                            firstVisibleIndex = firstSongIdx,
                            windowAhead = 36,
                            windowBehind = 10
                        )
                    }
                }
        }
    }

    val fastScrollAlphabetProvider: ((Int) -> String?)? = remember(selectedSortOrder, displaySongs) {
        when (selectedSortOrder) {
            PlaylistSortOrder.TITLE -> {
                { idx: Int ->
                    val songIdx = idx - 3
                    if (songIdx >= 0) displaySongs.getOrNull(songIdx)?.displayTitle?.trim()?.firstOrNull()?.uppercaseChar()?.toString() else null
                }
            }
            PlaylistSortOrder.ARTIST -> {
                { idx: Int ->
                    val songIdx = idx - 3
                    if (songIdx >= 0) displaySongs.getOrNull(songIdx)?.displayArtist?.trim()?.firstOrNull()?.uppercaseChar()?.toString() else null
                }
            }
            PlaylistSortOrder.ALBUM -> {
                { idx: Int ->
                    val songIdx = idx - 3
                    if (songIdx >= 0) displaySongs.getOrNull(songIdx)?.displayAlbum?.trim()?.firstOrNull()?.uppercaseChar()?.toString() else null
                }
            }
            else -> null
        }
    }

    // M3U Export ALWAYS exports in the user's custom order (`localEntries.map { it.song }`)
    // (or if they explicitly sorted and saved, localEntries holds that exact order).
    val m3uExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val customOrderedSongs = localEntries.map { it.song }.ifEmpty { displaySongs }
                onReorderSongs?.invoke(customOrderedSongs)
                val m3uContent = M3uParser.exportM3u(playlist.name, customOrderedSongs)
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(m3uContent.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                Toast.makeText(context, "Exported ${playlist.name} (${customOrderedSongs.size} tracks in custom order)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val artworkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onUpdatePlaylistArtwork(playlist.id, uri.toString())
            Toast.makeText(context, "Updated playlist artwork", Toast.LENGTH_SHORT).show()
        }
    }

    val totalDurationMs = remember(playlistSongs) { playlistSongs.sumOf { it.durationMs } }
    val totalMinutes = totalDurationMs / 1000 / 60

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Bar
            item(key = "playlist_top_bar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (selectedSortOrder == PlaylistSortOrder.CUSTOM) {
                                val updatedSongs = localEntries.map { it.song }
                                if (updatedSongs.isNotEmpty()) {
                                    onReorderSongs?.invoke(updatedSongs)
                                }
                            }
                            onBackClick()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(BentoCardDarkPurple)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = BentoTextPrimary
                        )
                    }

                    Text(
                        text = playlist.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 8.dp)
                    )

                    Box {
                        IconButton(
                            onClick = { showOptionsMenu = true },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BentoCardDarkPurple)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Playlist Options",
                                tint = BentoTextPrimary
                            )
                        }

                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            modifier = Modifier.background(BentoCardSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename Playlist", color = BentoTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    renameText = playlist.name
                                    showRenameDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Change Playlist Cover", color = BentoTextPrimary) },
                                onClick = {
                                    showOptionsMenu = false
                                    artworkPickerLauncher.launch("image/*")
                                }
                            )
                            if (playlist.artworkUri != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove Custom Cover", color = BentoTextSecondary) },
                                    onClick = {
                                        showOptionsMenu = false
                                        onUpdatePlaylistArtwork(playlist.id, null)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Export as M3U", color = BentoTextPrimary) },
                                onClick = {
                                    showOptionsMenu = false
                                    val customOrderedSongs = localEntries.map { it.song }
                                    if (customOrderedSongs.isNotEmpty()) {
                                        onReorderSongs?.invoke(customOrderedSongs)
                                    }
                                    m3uExportLauncher.launch("${playlist.name}.m3u")
                                }
                            )
                            if (onRemoveDuplicates != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove Duplicate Songs", color = BentoTextPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showOptionsMenu = false
                                        onRemoveDuplicates()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete Playlist", color = BentoAccentPinkText) },
                                onClick = {
                                    showOptionsMenu = false
                                    showDeleteConfirmation = true
                                }
                            )
                        }
                    }
                }
            }

            // Header Card with Large Artwork Block
            item(key = "playlist_header_card") {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = BentoCardSurface
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            com.example.ui.components.PlaylistCover(
                                playlist = playlist,
                                playlistSongs = displaySongs.ifEmpty { playlistSongs },
                                modifier = Modifier.size(80.dp),
                                cornerRadius = 16
                            )

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val totalHoursText = if (totalMinutes >= 60) {
                                    val hrs = totalMinutes / 60
                                    val mins = totalMinutes % 60
                                    if (mins > 0) "${hrs}h ${mins}m" else "${hrs}h"
                                } else "$totalMinutes mins"
                                Text(
                                    text = "${playlistSongs.size} tracks • $totalHoursText",
                                    fontSize = 13.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }

                        if (playlistSongs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            if (displaySongs.isNotEmpty()) {
                                                if (onPlayAll != null) {
                                                    onPlayAll(displaySongs)
                                                } else {
                                                    onSongClick(displaySongs.first(), displaySongs)
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play All",
                                            tint = BentoOnPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play All", color = BentoOnPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }

                                    Button(
                                        onClick = {
                                            if (displaySongs.isNotEmpty()) {
                                                if (onShuffleAll != null) {
                                                    onShuffleAll(displaySongs)
                                                } else {
                                                    val shuffled = displaySongs.shuffled()
                                                    if (shuffled.isNotEmpty()) onSongClick(shuffled.first(), shuffled)
                                                }
                                                Toast.makeText(context, "Shuffling ${displaySongs.size} tracks", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shuffle,
                                            contentDescription = "Shuffle",
                                            tint = BentoOnPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Shuffle", color = BentoOnPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sorting & Reordering Toolbar Card
            if (playlistSongs.isNotEmpty()) {
                item(key = "playlist_sort_toolbar") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = BentoCardDarkPurple),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sort,
                                        contentDescription = null,
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Sort & Reorder",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                }

                                if (selectedSortOrder != PlaylistSortOrder.CUSTOM) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        TextButton(
                                            onClick = {
                                                localEntries.clear()
                                                localEntries.addAll(displayEntries)
                                                selectedSortOrder = PlaylistSortOrder.CUSTOM
                                                val updatedSongs = displayEntries.map { it.song }
                                                lastCommittedOrderIds = updatedSongs.map { it.id }
                                                onReorderSongs?.invoke(updatedSongs)
                                                Toast.makeText(context, "Saved as custom playlist order", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Save Order", fontSize = 11.5.sp, color = BentoPrimary, fontWeight = FontWeight.Bold)
                                        }

                                        IconButton(
                                            onClick = { isAscending = !isAscending },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                contentDescription = if (isAscending) "Ascending" else "Descending",
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Drag ≡ handle to reorder",
                                        fontSize = 11.5.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }

                            // Horizontal Sort Chips Row
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(PlaylistSortOrder.entries) { sortOption ->
                                    val isSelected = selectedSortOrder == sortOption
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) BentoPrimary else BentoCardSurface)
                                            .clickable {
                                                selectedSortOrder = sortOption
                                                HapticUtils.performClickHaptic(context)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = sortOption.icon,
                                                contentDescription = null,
                                                tint = if (isSelected) BentoOnPrimary else BentoTextSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = sortOption.displayName,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) BentoOnPrimary else BentoTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Song Items
            if (displaySongs.isEmpty()) {
                item(key = "playlist_empty_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No songs in this playlist yet.",
                            fontSize = 14.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = if (selectedSortOrder == PlaylistSortOrder.CUSTOM) localEntries else displayEntries,
                    key = { _, entry -> entry.stableKey },
                    contentType = { _, _ -> "playlist_song_item" }
                ) { index, entry ->
                    val song = entry.song
                    val isCustomOrder = selectedSortOrder == PlaylistSortOrder.CUSTOM
                    val isCurrentSong = currentPlayingSongId == song.id
                    val isDragging = isCustomOrder && (
                        reorderableState.draggedKey == entry.stableKey ||
                            (reorderableState.draggedKey == null && reorderableState.draggedIndex == index)
                        )

                    val isAutoScrolling = reorderableState.autoScrollDirection != com.example.ui.components.AutoScrollDirection.NONE
                    val rowShape = remember { RoundedCornerShape(14.dp) }
                    val rowBgColor = when {
                        isDragging -> BentoCardDarkPurple
                        isCurrentSong -> BentoPrimary.copy(alpha = 0.14f)
                        else -> Color.Transparent
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 1.dp)
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = if (!reorderableState.isDragging || isDragging || isAutoScrolling) null else spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                            .zIndex(if (isDragging) 100f else 0f)
                    ) {
                        if (isDragging) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp),
                                shape = rowShape,
                                color = BentoCardDarkPurple.copy(alpha = 0.25f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.5.dp,
                                    BentoPrimary.copy(alpha = 0.45f)
                                )
                            ) {}
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = reorderableState.dragOffsetY
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                        shadowElevation = 16.dp.toPx()
                                    }
                                }
                                .clip(rowShape)
                                .background(rowBgColor)
                                .then(
                                    when {
                                        isDragging -> Modifier.border(1.5.dp, BentoPrimary, rowShape)
                                        isCurrentSong -> Modifier.border(1.dp, BentoPrimary.copy(alpha = 0.45f), rowShape)
                                        else -> Modifier
                                    }
                                )
                                .clickable(
                                    enabled = !reorderableState.isDragging && !reorderableState.isSettling
                                ) {
                                    val playQueue = if (isCustomOrder) localEntries.map { it.song } else displaySongs
                                    onSongClick(song, playQueue)
                                }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Interactive Drag Handle (exact Up-Next Queue technique)
                            if (isCustomOrder) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("playlist_drag_handle_${song.id}")
                                        .reorderableDragHandle(
                                            state = reorderableState,
                                            getIndex = {
                                                val found = localEntries.indexOfFirst { it.stableKey == entry.stableKey }
                                                if (found >= 0) found else index
                                            },
                                            getMinIndex = { 0 },
                                            getMaxIndex = { (localEntries.size - 1).coerceAtLeast(0) },
                                            getKey = { entry.stableKey },
                                            onStartFeedback = { HapticUtils.performTickHaptic(context) },
                                            onEndFeedback = { HapticUtils.performClickHaptic(context) }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        tint = if (isDragging) BentoPrimary else BentoTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            // Track index / Playing Equalizer badge
                            Box(
                                modifier = Modifier.width(26.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCurrentSong) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = "Currently Playing",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BentoTextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            AlbumArtView(
                                song = song,
                                modifier = Modifier.size(46.dp),
                                cornerRadius = 10
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.displayTitle,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isCurrentSong) BentoPrimary else BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val formattedDur = remember(song.durationMs) {
                                    val totalSec = (song.durationMs.coerceAtLeast(0L)) / 1000L
                                    String.format(Locale.US, "%d:%02d", totalSec / 60L, totalSec % 60L)
                                }
                                Text(
                                    text = "${song.displayArtist} • $formattedDur",
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { onFavoriteToggle(song) },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Toggle Favorite",
                                    tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { selectedSongForMenu = song },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Track Options",
                                    tint = BentoTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (displaySongs.size > 5) {
            FastScrollbar(
                listState = listState,
                totalItems = displaySongs.size + 3,
                itemIndexToLabel = fastScrollAlphabetProvider,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp)
            )
        }
    }

    selectedSongForMenu?.let { menuSong ->
        SongActionMenu(
            expanded = true,
            song = menuSong,
            isInPlaylist = true,
            playlistName = playlist.name,
            onDismissRequest = { selectedSongForMenu = null },
            onPlayNext = { onPlayNext(menuSong) },
            onAddToQueue = { onAddToQueue(menuSong) },
            onAddToPlaylist = { songForAddToPlaylist = menuSong },
            onRemoveFromPlaylist = { onRemoveSongFromPlaylist(playlist.id, menuSong.id) },
            onEditTags = if (onEditTags != null) { { onEditTags(menuSong) } } else null,
            onDeleteSong = { onDeleteSong(menuSong) }
        )
    }

    // Rename Playlist Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Playlist Name", color = BentoTextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = BentoTextPrimary,
                        unfocusedTextColor = BentoTextPrimary,
                        focusedBorderColor = BentoPrimary,
                        unfocusedBorderColor = BentoCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotBlank()) {
                            onRenamePlaylist(playlist.id, trimmed)
                            showRenameDialog = false
                            Toast.makeText(context, "Playlist renamed to \"$trimmed\"", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("Save", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Delete Playlist Confirmation Dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete playlist?", color = BentoTextPrimary) },
            text = {
                Text(
                    "This removes the playlist, not the music files.",
                    fontSize = 14.sp,
                    color = BentoTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeletePlaylist(playlist.id)
                        onBackClick()
                        Toast.makeText(context, "Playlist deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoAccentPinkText)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface
        )
    }

    // Add To Playlist Dialog
    songForAddToPlaylist?.let { targetSong ->
        AddToPlaylistDialog(
            song = targetSong,
            playlists = allPlaylists,
            onSelectPlaylist = { targetPlaylist ->
                onAddSongToPlaylist(targetPlaylist.id, targetSong.id)
            },
            onCreateNewPlaylist = { newName ->
                onCreateNewPlaylist(newName)
            },
            onDismiss = { songForAddToPlaylist = null }
        )
    }
}