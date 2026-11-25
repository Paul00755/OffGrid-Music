package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.model.SmartPlaylist
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.components.AlbumArtView
import com.example.ui.components.AutoScrollDirection
import com.example.ui.components.FastScrollbar
import com.example.ui.components.SmartPlaylistCriteriaDialog
import com.example.ui.components.SongActionMenu
import com.example.ui.components.rememberReorderableLazyListState
import com.example.ui.components.reorderableDragHandle
import com.example.ui.theme.*
import com.example.util.HapticUtils
import java.util.Locale

private data class SmartPlaylistTrackEntry(
    val song: Song,
    val stableKey: String
)

private fun buildSmartTrackEntries(songs: List<Song>, smartPlaylistId: Long): List<SmartPlaylistTrackEntry> {
    val occurrenceMap = HashMap<Long, Int>(songs.size)
    return songs.map { song ->
        val count = occurrenceMap.getOrDefault(song.id, 0)
        occurrenceMap[song.id] = count + 1
        SmartPlaylistTrackEntry(
            song = song,
            stableKey = "sp_${smartPlaylistId}_${song.id}_$count"
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartPlaylistDetailScreen(
    smartPlaylist: SmartPlaylist,
    allSongs: List<Song>,
    playbackState: PlaybackState,
    onBackClick: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onPlayAll: ((List<Song>) -> Unit)? = null,
    onDeleteSmartPlaylist: (Long) -> Unit,
    onUpdateSmartPlaylist: ((SmartPlaylist, SmartPlaylist) -> Unit)? = null,
    onReorderSongs: ((SmartPlaylist, List<Song>) -> Unit)? = null,
    onResetCustomOrder: ((SmartPlaylist) -> Unit)? = null,
    onFavoriteToggle: (Song) -> Unit = {},
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val filteredSongs = remember(smartPlaylist, allSongs) {
        smartPlaylist.filterSongs(allSongs)
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditRulesDialog by remember { mutableStateOf(false) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }

    // Optimistic local order state matching Up-Next Queue & PlaylistDetailScreen smoothness
    var lastCommittedOrderIds by remember(smartPlaylist.id) { mutableStateOf<List<Long>?>(null) }
    var hasDirtyCustomOrder by remember(smartPlaylist.id) { mutableStateOf(false) }

    val localEntries = remember(smartPlaylist.id) {
        mutableStateListOf<SmartPlaylistTrackEntry>().apply {
            addAll(buildSmartTrackEntries(filteredSongs, smartPlaylist.id))
        }
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 68.dp.toPx() }
    val currentSmartPlaylist by rememberUpdatedState(smartPlaylist)
    val currentOnReorderSongs by rememberUpdatedState(onReorderSongs)

    // Header items inside LazyColumn: Hero card (1) + Track list bar (1) = 2 when songs non-empty
    val headerItemCount = if (localEntries.isNotEmpty()) 2 else 1

    val reorderableState = rememberReorderableLazyListState(
        listState = listState,
        itemHeightPx = itemHeightPx,
        headerItemCount = headerItemCount,
        onMove = { from, to ->
            if (from != to && from in localEntries.indices && to in localEntries.indices) {
                try {
                    Snapshot.withMutableSnapshot {
                        if (kotlin.math.abs(from - to) == 1) {
                            val tmp = localEntries[from]
                            localEntries[from] = localEntries[to]
                            localEntries[to] = tmp
                        } else {
                            val moved = localEntries.removeAt(from)
                            val safeTo = to.coerceIn(0, localEntries.size)
                            localEntries.add(safeTo, moved)
                        }
                    }
                    hasDirtyCustomOrder = true
                    HapticUtils.performTickHaptic(context)
                } catch (e: Exception) {
                    android.util.Log.e("SmartPlaylistDetail", "Error swapping track $from -> $to", e)
                }
            }
        },
        onDragEnd = { _, _ ->
            try {
                val orderedSongs = localEntries.map { it.song }
                lastCommittedOrderIds = orderedSongs.map { it.id }
                hasDirtyCustomOrder = false
                currentOnReorderSongs?.invoke(currentSmartPlaylist, orderedSongs)
            } catch (e: Exception) {
                android.util.Log.e("SmartPlaylistDetail", "Error persisting smart playlist reorder", e)
            }
        }
    )

    // Sync filteredSongs -> localEntries whenever not actively dragging/settling
    LaunchedEffect(filteredSongs, reorderableState.isDragging, reorderableState.isSettling) {
        if (!reorderableState.isDragging && !reorderableState.isSettling) {
            val incomingIds = filteredSongs.map { it.id }
            val currentLocalIds = localEntries.map { it.song.id }
            val committed = lastCommittedOrderIds

            if (committed != null) {
                if (incomingIds == committed) {
                    lastCommittedOrderIds = null
                } else if (incomingIds.size == currentLocalIds.size && incomingIds.toSet() == currentLocalIds.toSet()) {
                    val songById = filteredSongs.associateBy { it.id }
                    for (i in localEntries.indices) {
                        val existing = localEntries[i]
                        val updated = songById[existing.song.id]
                        if (updated != null && updated != existing.song) {
                            localEntries[i] = existing.copy(song = updated)
                        }
                    }
                    return@LaunchedEffect
                } else {
                    lastCommittedOrderIds = null
                }
            }

            if (currentLocalIds != incomingIds) {
                localEntries.clear()
                localEntries.addAll(buildSmartTrackEntries(filteredSongs, smartPlaylist.id))
            } else {
                val songById = filteredSongs.associateBy { it.id }
                for (i in localEntries.indices) {
                    val existing = localEntries[i]
                    val updated = songById[existing.song.id]
                    if (updated != null && updated != existing.song) {
                        localEntries[i] = existing.copy(song = updated)
                    }
                }
            }
        }
    }

    // Flush any pending reorder on exit
    DisposableEffect(smartPlaylist.id) {
        onDispose {
            if (hasDirtyCustomOrder && localEntries.isNotEmpty()) {
                currentOnReorderSongs?.invoke(currentSmartPlaylist, localEntries.map { it.song })
            }
        }
    }

    BackHandler {
        if (hasDirtyCustomOrder && localEntries.isNotEmpty()) {
            currentOnReorderSongs?.invoke(currentSmartPlaylist, localEntries.map { it.song })
            hasDirtyCustomOrder = false
        }
        onBackClick()
    }

    // Preload artwork window ahead of scroll viewport for 120fps scrolling
    LaunchedEffect(smartPlaylist.id, filteredSongs.size) {
        if (filteredSongs.isNotEmpty()) {
            snapshotFlow { (listState.firstVisibleItemIndex - 3).coerceAtLeast(0) }
                .collect { firstIdx ->
                    val currentList = localEntries.map { it.song }
                    if (currentList.isNotEmpty()) {
                        com.example.ui.components.ArtworkResolutionCache.preloadWindow(
                            context = context,
                            songs = currentList,
                            firstVisibleIndex = firstIdx,
                            windowAhead = 24,
                            windowBehind = 8
                        )
                    }
                }
        }
    }

    if (showEditRulesDialog && onUpdateSmartPlaylist != null) {
        SmartPlaylistCriteriaDialog(
            allSongs = allSongs,
            initialSmartPlaylist = smartPlaylist,
            onDismiss = { showEditRulesDialog = false },
            onCreateSmartPlaylist = { updated ->
                lastCommittedOrderIds = null
                onUpdateSmartPlaylist(smartPlaylist, updated)
                showEditRulesDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = BentoCardSurface,
            title = { Text("Delete Smart Playlist", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete '${smartPlaylist.name}'?",
                    color = BentoTextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteSmartPlaylist(smartPlaylist.id)
                        onBackClick()
                    }
                ) {
                    Text("Delete", color = BentoError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            }
        )
    }

    val displayedSongs by remember {
        derivedStateOf { localEntries.map { it.song } }
    }
    val totalDurationText = remember(displayedSongs) {
        val totalMs = displayedSongs.sumOf { it.durationMs.coerceAtLeast(0L) }
        val totalMin = (totalMs / 60_000L).coerceAtLeast(0L)
        val hours = totalMin / 60L
        val mins = totalMin % 60L
        when {
            hours > 0 -> String.format(Locale.US, "%dh %dm", hours, mins)
            totalMin > 0 -> "${totalMin}m"
            else -> "<1m"
        }
    }
    val currentPlayingSongId = playbackState.currentSong?.id
    val canDragReorder = onReorderSongs != null && localEntries.size > 1

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BentoScreenBackground)
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (hasDirtyCustomOrder && localEntries.isNotEmpty()) {
                            currentOnReorderSongs?.invoke(currentSmartPlaylist, localEntries.map { it.song })
                            hasDirtyCustomOrder = false
                        }
                        onBackClick()
                    },
                    modifier = Modifier.testTag("smart_playlist_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = BentoTextPrimary
                    )
                }

                Text(
                    text = "Smart Playlist",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onUpdateSmartPlaylist != null) {
                        IconButton(
                            onClick = { showEditRulesDialog = true },
                            modifier = Modifier.testTag("smart_playlist_edit_rules_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Smart Playlist Rules",
                                tint = BentoPrimary
                            )
                        }
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("smart_playlist_delete_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Smart Playlist",
                            tint = BentoError.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // Scrollable Header + Draggable Track List
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("smart_playlist_tracks_list"),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Header Item 0: Smart Playlist Hero Card
                    item(key = "smart_playlist_hero_header") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(22.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                BentoPrimary.copy(alpha = 0.16f),
                                                BentoCardDarkPurple.copy(alpha = 0.94f)
                                            )
                                        )
                                    )
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(BentoPrimary.copy(alpha = 0.22f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = smartPlaylist.name,
                                            fontSize = 21.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = BentoTextPrimary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${displayedSongs.size} tracks · $totalDurationText",
                                            fontSize = 13.sp,
                                            color = BentoTextSecondary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                // Rule Badges
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    smartPlaylist.getCriteriaBadges().forEach { badge ->
                                        val isCustomBadge = badge == "Custom Order"
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (isCustomBadge) BentoPrimary.copy(alpha = 0.22f) else BentoCardSurface.copy(alpha = 0.72f),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isCustomBadge) BentoPrimary.copy(alpha = 0.6f) else BentoBorder.copy(alpha = 0.35f)
                                            )
                                        ) {
                                            Text(
                                                text = badge,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isCustomBadge) BentoPrimary else BentoTextPrimary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                // Play / Shuffle Actions
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            if (displayedSongs.isNotEmpty()) {
                                                if (onPlayAll != null) {
                                                    onPlayAll(displayedSongs)
                                                } else {
                                                    onSongClick(displayedSongs.first(), displayedSongs)
                                                }
                                            }
                                        },
                                        enabled = displayedSongs.isNotEmpty(),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("smart_playlist_play_all_button"),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BentoPrimary,
                                            contentColor = BentoOnPrimary
                                        )
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Play All", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            if (displayedSongs.isNotEmpty()) {
                                                onShufflePlay(displayedSongs)
                                                Toast.makeText(context, "Shuffling ${displayedSongs.size} tracks", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        enabled = displayedSongs.isNotEmpty(),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("smart_playlist_shuffle_button"),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = BentoCardSurface.copy(alpha = 0.9f),
                                            contentColor = BentoTextPrimary
                                        )
                                    ) {
                                        Icon(Icons.Default.Shuffle, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Shuffle", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Header Item 1: Reorder Hint & Custom Order Reset Toolbar
                    if (localEntries.isNotEmpty()) {
                        item(key = "smart_playlist_order_toolbar") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (canDragReorder) {
                                        Icon(
                                            imageVector = Icons.Default.DragHandle,
                                            contentDescription = null,
                                            tint = BentoTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Drag handle to reorder · saved automatically",
                                            fontSize = 11.5.sp,
                                            color = BentoTextSecondary
                                        )
                                    } else {
                                        Text(
                                            text = "Tracks",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextSecondary
                                        )
                                    }
                                }

                                if (smartPlaylist.hasCustomTrackOrder && onResetCustomOrder != null) {
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            lastCommittedOrderIds = null
                                            hasDirtyCustomOrder = false
                                            onResetCustomOrder(smartPlaylist)
                                            Toast.makeText(context, "Restored rule-based order", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(50),
                                        color = BentoPrimary.copy(alpha = 0.14f),
                                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                        modifier = Modifier.testTag("smart_playlist_reset_order_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.RestartAlt,
                                                contentDescription = "Reset custom order",
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "Reset Order",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Track Rows or Empty State
                    if (localEntries.isEmpty()) {
                        item(key = "smart_playlist_empty_state") {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 20.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = BentoCardSurface.copy(alpha = 0.65f),
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = BentoPrimary.copy(alpha = 0.75f),
                                        modifier = Modifier.size(34.dp)
                                    )
                                    Text(
                                        text = "No matching tracks yet",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                    Text(
                                        text = "Edit the rules above or listen to more songs and this mix will populate automatically.",
                                        fontSize = 12.5.sp,
                                        color = BentoTextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = localEntries,
                            key = { _, entry -> entry.stableKey },
                            contentType = { _, _ -> "smart_playlist_track" }
                        ) { index, entry ->
                            val song = entry.song
                            val isCurrentSong = currentPlayingSongId == song.id
                            val isDragging = canDragReorder && (
                                reorderableState.draggedKey == entry.stableKey ||
                                    (reorderableState.draggedKey == null && reorderableState.draggedIndex == index)
                                )
                            val isAutoScrolling = reorderableState.autoScrollDirection != AutoScrollDirection.NONE
                            val rowShape = remember { RoundedCornerShape(14.dp) }
                            val rowBgColor = when {
                                isDragging -> BentoCardDarkPurple
                                isCurrentSong -> BentoPrimary.copy(alpha = 0.14f)
                                else -> BentoCardSurface.copy(alpha = 0.45f)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = if (!reorderableState.isDragging || isDragging || isAutoScrolling) {
                                            null
                                        } else {
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        }
                                    )
                                    .zIndex(if (isDragging) 100f else 0f)
                            ) {
                                // Drop target slot indicator
                                if (isDragging) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(62.dp),
                                        shape = rowShape,
                                        color = BentoCardDarkPurple.copy(alpha = 0.25f),
                                        border = BorderStroke(1.5.dp, BentoPrimary.copy(alpha = 0.48f))
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
                                            onSongClick(song, localEntries.map { it.song })
                                        }
                                        .padding(horizontal = 6.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Smooth Drag Handle (matches Up-Next Queue & PlaylistDetailScreen)
                                    if (canDragReorder) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .testTag("smart_playlist_drag_handle_${song.id}")
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
                                                contentDescription = "Drag to reorder track",
                                                tint = if (isDragging) BentoPrimary else BentoTextSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    // Position Index / Playing Equalizer Indicator
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

                                    // Album Art
                                    AlbumArtView(
                                        song = song,
                                        modifier = Modifier.size(46.dp),
                                        cornerRadius = 10
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Track Metadata
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.displayTitle,
                                            fontSize = 14.sp,
                                            fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isCurrentSong) BentoPrimary else BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${song.displayArtist} · ${formatSmartTrackTime(song.durationMs)}",
                                            fontSize = 12.sp,
                                            color = BentoTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // More Actions Button
                                    Box {
                                        IconButton(
                                            onClick = { selectedSongForMenu = song },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Song options",
                                                tint = BentoTextSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        if (selectedSongForMenu?.id == song.id) {
                                            SongActionMenu(
                                                expanded = true,
                                                song = song,
                                                isInPlaylist = false,
                                                onDismissRequest = { selectedSongForMenu = null },
                                                onPlayNext = { onPlayNext(song) },
                                                onAddToQueue = { onAddToQueue(song) },
                                                onAddToPlaylist = { onAddToPlaylist(song) },
                                                onEditTags = onEditTags?.let { { it(song) } },
                                                onDeleteSong = { onDeleteSong(song) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (localEntries.size > 10 && !reorderableState.isDragging) {
                    FastScrollbar(
                        listState = listState,
                        totalItems = localEntries.size,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp, top = 8.dp, bottom = 120.dp)
                    )
                }
            }
        }
    }
}

private fun formatSmartTrackTime(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
