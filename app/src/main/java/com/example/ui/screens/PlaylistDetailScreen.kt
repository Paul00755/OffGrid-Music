package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.zIndex
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.scanner.M3uParser
import com.example.service.PlaybackState
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.BentoCard
import com.example.ui.components.FastScrollbar
import com.example.ui.components.SongActionMenu
import com.example.ui.components.SongItem
import com.example.ui.components.rememberReorderableLazyListState
import com.example.ui.components.reorderableDragHandle
import com.example.ui.theme.*
import com.example.util.HapticUtils

data class PlaylistSongEntry(val stableKey: String, val song: Song)

fun buildPlaylistEntries(songs: List<Song>, playlistId: Long): List<PlaylistSongEntry> {
    val occurrences = mutableMapOf<Long, Int>()
    return songs.mapIndexed { index, song ->
        val count = occurrences.getOrDefault(song.id, 0) + 1
        occurrences[song.id] = count
        PlaylistSongEntry(
            stableKey = "pl_${playlistId}_${song.id}_${count}_${index}_${song.path.hashCode()}",
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
    var lastSavedOrderIds by remember(playlist.id) { mutableStateOf<List<Long>?>(null) }

    // Maintain a mutable list of entries for live drag-and-drop reordering with stable composite keys
    val initialEntries = remember(playlist.id, playlistSongs) {
        buildPlaylistEntries(playlistSongs, playlist.id)
    }
    val localEntries = remember(playlist.id) {
        mutableStateListOf<PlaylistSongEntry>().apply { addAll(initialEntries) }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 68.dp.toPx() }

    val reorderableState = rememberReorderableLazyListState(
        listState = listState,
        itemHeightPx = itemHeightPx,
        onMove = { from, to ->
            if (from != to && from in localEntries.indices && to in localEntries.indices) {
                try {
                    val item = localEntries.removeAt(from)
                    val safeTo = to.coerceIn(0, localEntries.size)
                    localEntries.add(safeTo, item)
                    HapticUtils.performTickHaptic(context)
                } catch (e: Exception) {
                    android.util.Log.e("PlaylistDetailScreen", "Error in onMove: from=$from to=$to", e)
                }
            }
        },
        onDragEnd = { _, _ ->
            try {
                val updatedSongs = localEntries.map { it.song }
                lastSavedOrderIds = updatedSongs.map { it.id }
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
            val currentIds = localEntries.map { it.song.id }
            val newIds = playlistSongs.map { it.id }

            if (localEntries.isEmpty() && playlistSongs.isNotEmpty()) {
                localEntries.clear()
                localEntries.addAll(buildPlaylistEntries(playlistSongs, playlist.id))
            } else if (currentIds.size != newIds.size || currentIds.toSet() != newIds.toSet()) {
                // Songs were added or deleted from playlist
                val existingMap = localEntries.associateBy { it.song.id }
                val remainingEntries = localEntries.filter { entry -> newIds.contains(entry.song.id) }.toMutableList()
                val addedSongs = playlistSongs.filter { song -> !existingMap.containsKey(song.id) }
                if (addedSongs.isNotEmpty()) {
                    val baseIndex = remainingEntries.size + 1
                    val newEntries = addedSongs.mapIndexed { idx, song ->
                        PlaylistSongEntry(
                            stableKey = "pl_${playlist.id}_${song.id}_added_${baseIndex + idx}_${song.path.hashCode()}",
                            song = song
                        )
                    }
                    val firstAddedId = addedSongs.first().id
                    val isAddedAtTop = playlistSongs.firstOrNull()?.id == firstAddedId
                    if (isAddedAtTop) {
                        remainingEntries.addAll(0, newEntries)
                    } else {
                        remainingEntries.addAll(newEntries)
                    }
                }
                localEntries.clear()
                localEntries.addAll(remainingEntries)
            } else {
                // Same song set: If newIds matches lastSavedOrderIds, DB write is confirmed
                if (lastSavedOrderIds != null && newIds == lastSavedOrderIds) {
                    lastSavedOrderIds = null
                }
                // Under NO circumstances do we reset or reorder localEntries when the set of songs is unchanged.
                // In Custom Order mode, localEntries is the true source of order.
                // CRITICAL FIX: Live update song metadata (title, artist, album, artwork, lyrics) for existing entries!
                val updatedSongMap = playlistSongs.associateBy { it.id }
                for (i in localEntries.indices) {
                    val currentEntry = localEntries[i]
                    val updated = updatedSongMap[currentEntry.song.id]
                    if (updated != null && (updated != currentEntry.song ||
                            updated.title != currentEntry.song.title ||
                            updated.artist != currentEntry.song.artist ||
                            updated.album != currentEntry.song.album ||
                            updated.artworkUri != currentEntry.song.artworkUri)) {
                        localEntries[i] = currentEntry.copy(song = updated)
                    }
                }
            }
        }
    }

    val displayEntries = remember(localEntries.toList(), selectedSortOrder, isAscending) {
        val sorted = when (selectedSortOrder) {
            PlaylistSortOrder.CUSTOM -> localEntries.toList()
            PlaylistSortOrder.LATEST -> localEntries.sortedByDescending { it.song.dateAdded }
            PlaylistSortOrder.TITLE -> localEntries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.song.displayTitle })
            PlaylistSortOrder.ARTIST -> localEntries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.song.displayArtist })
            PlaylistSortOrder.ALBUM -> localEntries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.song.displayAlbum })
            PlaylistSortOrder.DURATION -> localEntries.sortedBy { it.song.durationMs }
        }
        if (!isAscending && selectedSortOrder != PlaylistSortOrder.CUSTOM) {
            sorted.reversed()
        } else {
            sorted
        }
    }

    val displaySongs = remember(displayEntries) { displayEntries.map { it.song } }

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

    val m3uExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val songsToExport = if (selectedSortOrder == PlaylistSortOrder.CUSTOM) localEntries.map { it.song } else displaySongs
                val m3uContent = M3uParser.exportM3u(playlist.name, songsToExport)
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(m3uContent.toByteArray())
                }
                Toast.makeText(context, "Exported ${playlist.name} as M3U", Toast.LENGTH_SHORT).show()
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

    val totalDurationMs = playlistSongs.sumOf { it.durationMs }
    val totalMinutes = totalDurationMs / 1000 / 60

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // Top Bar
        item {
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
                    modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)
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
        item {
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
                            playlistSongs = playlistSongs,
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
            item {
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
                                            lastSavedOrderIds = updatedSongs.map { it.id }
                                            onReorderSongs?.invoke(updatedSongs)
                                            Toast.makeText(context, "Saved as playlist order", Toast.LENGTH_SHORT).show()
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
            item {
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
                val isDragging = isCustomOrder && reorderableState.draggedIndex == index

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(
                            placementSpec = if (isDragging) null else spring(
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
                                .height(64.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = BentoCardDarkPurple.copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                BentoPrimary.copy(alpha = 0.45f)
                            )
                        ) {}
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                if (isDragging) {
                                    translationY = reorderableState.dragOffsetY
                                    scaleX = 1.03f
                                    scaleY = 1.03f
                                    rotationZ = (reorderableState.dragOffsetY * 0.02f).coerceIn(-3f, 3f)
                                    shadowElevation = 16.dp.toPx()
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = if (isDragging) BentoCardDarkPurple else Color.Transparent),
                        border = if (isDragging) androidx.compose.foundation.BorderStroke(1.5.dp, BentoPrimary) else null,
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 12.dp else 0.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Interactive Reorder Drag Handle (Only active in Custom Order)
                            if (isCustomOrder) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
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
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                SongItem(
                                    song = song,
                                    isPlaying = playbackState.currentSong?.id == song.id,
                                    onClick = {
                                        if (!reorderableState.isDragging && !reorderableState.isSettling) {
                                            onSongClick(song, displaySongs)
                                        }
                                    },
                                    onFavoriteToggle = { onFavoriteToggle(song) },
                                    onMoreClick = { selectedSongForMenu = song }
                                )

                                if (selectedSongForMenu?.id == song.id) {
                                    SongActionMenu(
                                        expanded = true,
                                        song = song,
                                        isInPlaylist = true,
                                        playlistName = playlist.name,
                                        onDismissRequest = { selectedSongForMenu = null },
                                        onPlayNext = { onPlayNext(song) },
                                        onAddToQueue = { onAddToQueue(song) },
                                        onAddToPlaylist = { songForAddToPlaylist = song },
                                        onRemoveFromPlaylist = { onRemoveSongFromPlaylist(playlist.id, song.id) },
                                        onEditTags = if (onEditTags != null) { { onEditTags(song) } } else null,
                                        onDeleteSong = { onDeleteSong(song) }
                                    )
                                }
                            }
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
                itemLabelProvider = fastScrollAlphabetProvider,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp, top = 8.dp, bottom = 120.dp)
            )
        }
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
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
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
