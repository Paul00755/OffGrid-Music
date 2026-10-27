package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.theme.*
import com.example.util.HapticUtils

/**
 * Stable wrapper for queue items to prevent duplicate key crashes in Compose LazyColumn
 * even when the exact same song appears multiple times in the queue.
 */
data class QueueEntryItem(
    val stableKey: String,
    val song: Song
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextQueueSheet(
    playbackState: PlaybackState,
    onDismiss: () -> Unit,
    onSongClick: (Int) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onUpdateQueue: ((List<Song>) -> Unit)? = null,
    onRemoveFromQueue: (Int) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onRemoveDuplicates: (() -> Unit)? = null,
    onPlayNext: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val queue = playbackState.queue
    val currentSong = playbackState.currentSong
    val currentIndex = playbackState.queueIndex

    fun buildQueueEntries(songList: List<Song>): List<QueueEntryItem> {
        val occurrenceMap = mutableMapOf<Long, Int>()
        return songList.map { song ->
            val count = occurrenceMap.getOrDefault(song.id, 0)
            occurrenceMap[song.id] = count + 1
            QueueEntryItem(
                stableKey = "q_${song.id}_occ${count}_${song.path.hashCode()}",
                song = song
            )
        }
    }

    val initialEntries = remember {
        buildQueueEntries(queue)
    }

    val localQueue = remember {
        mutableStateListOf<QueueEntryItem>().apply { addAll(initialEntries) }
    }

    // Track the last order committed by user drag-and-drop so an asynchronous PlaybackState emission
    // never overwrites localQueue with stale pre-drag order before PlaybackManager finishes applying updateQueueOrder.
    var lastCommittedOrderIds by remember { mutableStateOf<List<Long>?>(null) }

    // Track the exact QueueEntryItem (by stableKey) that is currently playing
    var playingEntryKey by remember {
        val initIdx = if (currentIndex in initialEntries.indices) currentIndex
        else initialEntries.indexOfFirst { it.song.id == currentSong?.id }.coerceAtLeast(0)
        mutableStateOf(initialEntries.getOrNull(initIdx)?.stableKey)
    }

    val density = LocalDensity.current
    val itemHeightPx = with(density) { 66.dp.toPx() }

    val currentActualIndex = remember(localQueue.toList(), playingEntryKey, currentSong?.id, currentIndex) {
        val byKey = if (playingEntryKey != null) {
            localQueue.indexOfFirst { it.stableKey == playingEntryKey }
        } else -1
        when {
            byKey >= 0 -> byKey
            currentIndex in localQueue.indices && (currentSong == null || localQueue[currentIndex].song.id == currentSong.id) -> currentIndex
            currentSong != null -> localQueue.indexOfFirst { it.song.id == currentSong.id }.let { if (it >= 0) it else 0 }
            currentIndex in localQueue.indices -> currentIndex
            else -> 0
        }
    }

    val safeInitialIndex = remember {
        if (localQueue.isNotEmpty() && currentActualIndex in localQueue.indices) {
            (currentActualIndex - 1).coerceIn(0, localQueue.size - 1)
        } else 0
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = safeInitialIndex
    )

    val reorderableState = rememberReorderableLazyListState(
        listState = listState,
        itemHeightPx = itemHeightPx,
        onMove = { from, to ->
            if (from != to && from in localQueue.indices && to in localQueue.indices) {
                try {
                    val item = localQueue.removeAt(from)
                    val safeTo = to.coerceIn(0, localQueue.size)
                    localQueue.add(safeTo, item)
                    lastCommittedOrderIds = localQueue.map { it.song.id }
                    HapticUtils.performTickHaptic(context)
                } catch (e: Exception) {
                    android.util.Log.e("UpNextQueueSheet", "Error in onMove: from=$from to=$to", e)
                }
            }
        },
        onDragEnd = { from, to ->
            try {
                val updatedSongs = localQueue.map { it.song }
                lastCommittedOrderIds = updatedSongs.map { it.id }
                if (onUpdateQueue != null) {
                    onUpdateQueue.invoke(updatedSongs)
                } else if (from != to) {
                    onReorder.invoke(from, to)
                }
            } catch (e: Exception) {
                android.util.Log.e("UpNextQueueSheet", "Error in onDragEnd", e)
            }
        }
    )

    // Prevent ModalBottomSheet from intercepting vertical drag/scroll gestures inside the queue list
    val sheetScrollGuard = remember(reorderableState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                // Consume leftover upward/downward scroll so the bottom sheet doesn't drag/collapse while scrolling or reordering
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return available
            }
        }
    }

    LaunchedEffect(currentSong?.id) {
        if (!reorderableState.isDragging && !reorderableState.isSettling && localQueue.isNotEmpty()) {
            val targetIdx = if (currentIndex in localQueue.indices && localQueue[currentIndex].song.id == currentSong?.id) {
                currentIndex
            } else {
                localQueue.indexOfFirst { it.song.id == currentSong?.id }
            }
            if (targetIdx in localQueue.indices) {
                playingEntryKey = localQueue[targetIdx].stableKey
            }
        }
    }

    LaunchedEffect(queue, currentIndex, currentSong?.id, reorderableState.isDragging, reorderableState.isSettling) {
        if (!reorderableState.isDragging && !reorderableState.isSettling) {
            val incomingIds = queue.map { it.id }
            val currentLocalIds = localQueue.map { it.song.id }

            if (lastCommittedOrderIds != null) {
                if (incomingIds == lastCommittedOrderIds) {
                    lastCommittedOrderIds = null
                } else if (incomingIds.size == currentLocalIds.size && incomingIds.toSet() == currentLocalIds.toSet()) {
                    // Ignore stale intermediate PlaybackState emission that hasn't reflected our committed order yet
                    return@LaunchedEffect
                } else {
                    lastCommittedOrderIds = null
                }
            }

            val currentSongs = localQueue.map { it.song }
            if (currentSongs != queue && queue.isNotEmpty()) {
                val newEntries = buildQueueEntries(queue)
                localQueue.clear()
                localQueue.addAll(newEntries)
            }
            val resolvedIdx = if (currentIndex in localQueue.indices && (currentSong == null || localQueue[currentIndex].song.id == currentSong.id)) {
                currentIndex
            } else {
                localQueue.indexOfFirst { it.song.id == currentSong?.id }
            }
            if (resolvedIdx in localQueue.indices) {
                playingEntryKey = localQueue[resolvedIdx].stableKey
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                val currentSongs = localQueue.map { it.song }
                if (currentSongs.isNotEmpty() && currentSongs != queue) {
                    onUpdateQueue?.invoke(currentSongs)
                }
            } catch (e: Exception) {
                android.util.Log.e("UpNextQueueSheet", "Error in onDispose queue persistence", e)
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = {
            try {
                val currentSongs = localQueue.map { it.song }
                if (currentSongs.isNotEmpty() && currentSongs != queue) {
                    onUpdateQueue?.invoke(currentSongs)
                }
            } catch (e: Exception) {
                android.util.Log.e("UpNextQueueSheet", "Error in onDismissRequest queue persistence", e)
            }
            onDismiss()
        },
        containerColor = BentoCardSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Up Next",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = if (playbackState.isShuffleEnabled) "${localQueue.size} songs • Shuffle Active" else "${localQueue.size} songs in queue",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (playbackState.isShuffleEnabled) BentoPrimary else BentoTextSecondary
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (playbackState.isShuffleEnabled) BentoPrimary.copy(alpha = 0.2f) else Color.Transparent,
                        modifier = Modifier.size(38.dp)
                    ) {
                        IconButton(onClick = {
                            HapticUtils.performClickHaptic(context)
                            onToggleShuffle()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Toggle Shuffle",
                                tint = if (playbackState.isShuffleEnabled) BentoPrimary else BentoTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = if (playbackState.repeatMode != 0) BentoPrimary.copy(alpha = 0.2f) else Color.Transparent,
                        modifier = Modifier.size(38.dp)
                    ) {
                        IconButton(onClick = {
                            HapticUtils.performClickHaptic(context)
                            onToggleRepeat()
                        }) {
                            Icon(
                                imageVector = if (playbackState.repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Toggle Repeat",
                                tint = if (playbackState.repeatMode != 0) BentoPrimary else BentoTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (onRemoveDuplicates != null) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier.size(38.dp)
                        ) {
                            IconButton(onClick = {
                                HapticUtils.performClickHaptic(context)
                                onRemoveDuplicates()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Remove Duplicates from Queue",
                                    tint = BentoTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            val currentSongs = localQueue.map { it.song }
                            if (currentSongs.isNotEmpty() && currentSongs != queue) {
                                onUpdateQueue?.invoke(currentSongs)
                            }
                            onDismiss()
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Queue",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (localQueue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Queue is empty", color = BentoTextSecondary, fontSize = 15.sp)
                }
            } else {
                val activePlayingSong = localQueue.getOrNull(currentActualIndex)?.song ?: currentSong
                if (activePlayingSong != null) {
                    Text(
                        text = "NOW PLAYING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoPrimary,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BentoCardDarkPurple
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Now Playing",
                                tint = BentoPrimary,
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(end = 4.dp)
                            )

                            Spacer(modifier = Modifier.width(4.dp))

                            AlbumArtView(
                                song = activePlayingSong,
                                modifier = Modifier.size(46.dp),
                                cornerRadius = 8
                            )

                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activePlayingSong.displayTitle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = activePlayingSong.displayArtist,
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(BentoPrimary.copy(alpha = 0.2f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (playbackState.isPlaying) "Now Playing" else "Paused",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "QUEUE TRACKS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Text(
                            text = "Hold & drag ≡ to reorder",
                            fontSize = 11.sp,
                            color = BentoTextSubtle
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .nestedScroll(sheetScrollGuard)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = localQueue,
                            key = { _, entry -> entry.stableKey },
                            contentType = { _, _ -> "queue_track_item" }
                        ) { index, entry ->
                            val song = entry.song
                            val isCurrent = (playingEntryKey != null && entry.stableKey == playingEntryKey) ||
                                    (playingEntryKey == null && index == currentActualIndex)
                            val isPlayed = !isCurrent && index < currentActualIndex
                            val isDragging = (reorderableState.draggedKey != null && reorderableState.draggedKey == entry.stableKey) ||
                                    (reorderableState.draggedKey == null && reorderableState.draggedIndex == index)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = if (isDragging) null else spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                    .zIndex(if (isDragging) 100f else 0f)
                            ) {
                                if (isDragging) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(58.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = BentoCardDarkPurple.copy(alpha = 0.20f),
                                        border = BorderStroke(
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
                                                scaleX = 1.02f
                                                scaleY = 1.02f
                                                shadowElevation = 16.dp.toPx()
                                            }
                                        }
                                        .clickable {
                                            if (!reorderableState.isDragging && !reorderableState.isSettling) {
                                                if (index in localQueue.indices) {
                                                    playingEntryKey = entry.stableKey
                                                    onSongClick(index)
                                                }
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            isDragging -> BentoCardDarkPurple
                                            isCurrent -> BentoCardDarkPurple.copy(alpha = 0.85f)
                                            else -> BentoCardSurface
                                        }
                                    ),
                                    border = when {
                                        isDragging -> BorderStroke(1.5.dp, BentoPrimary)
                                        isCurrent -> BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.5f))
                                        else -> null
                                    },
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 12.dp else 0.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Drag Handle
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .reorderableDragHandle(
                                                    state = reorderableState,
                                                    getIndex = {
                                                        val found = localQueue.indexOfFirst { it.stableKey == entry.stableKey }
                                                        if (found >= 0) found else index
                                                    },
                                                    getMinIndex = { 0 },
                                                    getMaxIndex = { (localQueue.size - 1).coerceAtLeast(0) },
                                                    getKey = { entry.stableKey },
                                                    onStartFeedback = { HapticUtils.performTickHaptic(context) },
                                                    onEndFeedback = { HapticUtils.performClickHaptic(context) }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DragHandle,
                                                contentDescription = "Drag to reorder track",
                                                tint = if (isDragging || isCurrent) BentoPrimary else BentoTextSecondary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }

                                        // Track Status / Queue Position Indicator
                                        Box(
                                            modifier = Modifier.width(28.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (isCurrent) {
                                                Icon(
                                                    imageVector = if (playbackState.isPlaying) Icons.Default.GraphicEq else Icons.Default.PauseCircleFilled,
                                                    contentDescription = "Now Playing",
                                                    tint = BentoPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else {
                                                Text(
                                                    text = "${index + 1}",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isPlayed) BentoTextSubtle else BentoTextSecondary
                                                )
                                            }
                                        }

                                        AlbumArtView(
                                            song = song,
                                            modifier = Modifier
                                                .size(42.dp)
                                                .graphicsLayer(alpha = if (isPlayed) 0.55f else 1f),
                                            cornerRadius = 8
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = song.displayTitle,
                                                fontSize = 14.sp,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isCurrent) BentoPrimary else if (isPlayed) BentoTextSecondary else BentoTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (isCurrent) {
                                                    Text(
                                                        text = "Now Playing • ",
                                                        fontSize = 11.sp,
                                                        color = BentoPrimary,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                } else if (isPlayed) {
                                                    Text(
                                                        text = "Played • ",
                                                        fontSize = 11.sp,
                                                        color = BentoTextSubtle,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                                Text(
                                                    text = song.displayArtist,
                                                    fontSize = 12.sp,
                                                    color = BentoTextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        var showItemMenu by remember { mutableStateOf(false) }

                                        Box {
                                            IconButton(
                                                onClick = { showItemMenu = true },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "Options",
                                                    tint = BentoTextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            if (showItemMenu) {
                                                SongActionMenu(
                                                    expanded = true,
                                                    song = song,
                                                    isInQueue = true,
                                                    onDismissRequest = { showItemMenu = false },
                                                    onPlayNow = {
                                                        if (index in localQueue.indices) {
                                                            playingEntryKey = entry.stableKey
                                                            onSongClick(index)
                                                        }
                                                    },
                                                    onPlayNext = { onPlayNext(song) },
                                                    onAddToQueue = { /* already in queue */ },
                                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                                    onRemoveFromQueue = {
                                                        if (index in localQueue.indices) {
                                                            localQueue.removeAt(index)
                                                            lastCommittedOrderIds = localQueue.map { it.song.id }
                                                            onRemoveFromQueue(index)
                                                        }
                                                    },
                                                    onMoveToTop = {
                                                        if (index > 0 && index in localQueue.indices) {
                                                            val itm = localQueue.removeAt(index)
                                                            val topTarget = (currentActualIndex + 1).coerceIn(0, localQueue.size)
                                                            localQueue.add(topTarget, itm)
                                                            val updatedSongs = localQueue.map { it.song }
                                                            lastCommittedOrderIds = updatedSongs.map { it.id }
                                                            onUpdateQueue?.invoke(updatedSongs)
                                                        }
                                                    },
                                                    onDeleteSong = { /* Handled at app level */ }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    FastScrollbar(
                        listState = listState,
                        totalItems = localQueue.size,
                        bottomPadding = 24.dp,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = 12.dp)
                    )
                }
            }
        }
    }
}