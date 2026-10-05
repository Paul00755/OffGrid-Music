package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val coroutineScope = rememberCoroutineScope()
    val queue = playbackState.queue
    val currentSong = playbackState.currentSong
    val currentIndex = playbackState.queueIndex

    // Map list of songs to unique QueueEntryItem instances with deterministic stable keys (independent of position index)
    fun buildQueueEntries(songList: List<Song>): List<QueueEntryItem> {
        val occurrenceMap = mutableMapOf<Long, Int>()
        return songList.map { song ->
            val count = occurrenceMap.getOrDefault(song.id, 0)
            occurrenceMap[song.id] = count + 1
            QueueEntryItem(
                stableKey = "q_${song.id}_occ${count}_${song.path.hashCode()}_${song.artworkUri ?: ""}_${song.title.hashCode()}",
                song = song
            )
        }
    }

    val initialEntries = remember(queue) {
        buildQueueEntries(queue)
    }

    val localQueue = remember {
        mutableStateListOf<QueueEntryItem>().apply { addAll(initialEntries) }
    }

    val density = LocalDensity.current
    val itemHeightPx = with(density) { 68.dp.toPx() }

    val currentActualIndex = if (currentIndex in localQueue.indices && (currentSong == null || localQueue[currentIndex].song.id == currentSong.id)) {
        currentIndex
    } else if (currentIndex in localQueue.indices) {
        currentIndex
    } else {
        val found = localQueue.indexOfFirst { it.song.id == currentSong?.id }
        if (found >= 0) found else 0
    }

    val safeInitialIndex = remember(currentActualIndex, localQueue.size) {
        if (localQueue.isNotEmpty() && currentActualIndex in localQueue.indices) {
            (currentActualIndex - 1).coerceIn(0, localQueue.size - 1)
        } else 0
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = safeInitialIndex
    )

    LaunchedEffect(currentActualIndex) {
        if (localQueue.isNotEmpty() && currentActualIndex in localQueue.indices) {
            val targetScroll = (currentActualIndex - 1).coerceAtLeast(0)
            listState.animateScrollToItem(targetScroll)
        }
    }

    // Reorderable list state with background thread offloading
    val reorderableState = rememberReorderableLazyListState(
        listState = listState,
        itemHeightPx = itemHeightPx,
        onMove = { from, to ->
            if (from != to && from in localQueue.indices && to in localQueue.indices) {
                try {
                    val item = localQueue.removeAt(from)
                    val safeTo = to.coerceIn(0, localQueue.size)
                    localQueue.add(safeTo, item)
                    HapticUtils.performTickHaptic(context)
                } catch (e: Exception) {
                    android.util.Log.e("UpNextQueueSheet", "Error in onMove: from=$from to=$to", e)
                }
            }
        },
        onDragEnd = { from, to ->
            try {
                val updatedSongs = localQueue.map { it.song }
                if (onUpdateQueue != null) {
                    onUpdateQueue.invoke(updatedSongs)
                } else if (onReorder != null && from != null && to != null) {
                    onReorder.invoke(from, to)
                }
            } catch (e: Exception) {
                android.util.Log.e("UpNextQueueSheet", "Error in onDragEnd", e)
            }
        }
    )

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

    LaunchedEffect(queue, reorderableState.isDragging, reorderableState.isSettling) {
        if (!reorderableState.isDragging && !reorderableState.isSettling) {
            val currentSongs = localQueue.map { it.song }
            if (currentSongs != queue && queue.isNotEmpty()) {
                val newEntries = buildQueueEntries(queue)
                localQueue.clear()
                localQueue.addAll(newEntries)
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
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
                    // Shuffle Toggle
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

                    // Repeat Toggle
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

                    // Remove Duplicates from Queue
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
                            if (reorderableState.isDragging) {
                                onUpdateQueue?.invoke(localQueue.map { it.song })
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

            Spacer(modifier = Modifier.height(16.dp))

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
                Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Currently Playing Section
                        if (currentSong != null) {
                            val displayNum = if (currentActualIndex in localQueue.indices) currentActualIndex + 1 else 1

                            item(key = "header_now_playing") {
                                Text(
                                    text = "NOW PLAYING • #$displayNum",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(vertical = 4.dp)
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
                                        Text(
                                            text = "$displayNum",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary,
                                            modifier = Modifier.width(28.dp)
                                        )

                                        AlbumArtView(
                                            song = currentSong,
                                            modifier = Modifier.size(48.dp),
                                            cornerRadius = 8
                                        )

                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = currentSong.displayTitle,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = currentSong.displayArtist,
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
                                                text = "Playing",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoPrimary
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "UP NEXT",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextSecondary,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                    Text(
                                        text = "Hold & drag to reorder",
                                        fontSize = 11.sp,
                                        color = BentoTextSubtle
                                    )
                                }
                            }
                        }

                        // Up Next Queue Items with Unique Composite Keys & Native Performance
                        itemsIndexed(
                            items = localQueue,
                            key = { _, entry -> entry.stableKey }
                        ) { index, entry ->
                            val song = entry.song
                            val isCurrent = index == currentActualIndex
                            val isPlayed = !isCurrent && index < currentActualIndex
                            val isDragging = reorderableState.draggedIndex == index

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
                                            .height(58.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = BentoCardDarkPurple.copy(alpha = 0.20f),
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
                                        }
                                        .clickable {
                                            if (!reorderableState.isDragging && !reorderableState.isSettling) {
                                                if (index in localQueue.indices) {
                                                    onSongClick(index)
                                                }
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            isDragging -> BentoCardDarkPurple
                                            isCurrent -> BentoCardDarkPurple.copy(alpha = 0.75f)
                                            else -> BentoCardSurface
                                        }
                                    ),
                                    border = if (isDragging) {
                                        androidx.compose.foundation.BorderStroke(1.5.dp, BentoPrimary)
                                    } else null,
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 12.dp else 0.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Fluid Reorderable Drag Handle (All tracks including currently playing)
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
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
                                                tint = if (isDragging) BentoPrimary else if (isCurrent) BentoPrimary else BentoTextSecondary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        // Song Queue Position Number
                                        Text(
                                            text = "${index + 1}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isCurrent) BentoPrimary else if (isPlayed) BentoTextSubtle else BentoTextSecondary,
                                            modifier = Modifier.width(26.dp)
                                        )

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
                                                if (isPlayed) {
                                                    Text(
                                                        text = "Played • ",
                                                        fontSize = 11.sp,
                                                        color = BentoTextSubtle,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                } else if (isCurrent) {
                                                    Text(
                                                        text = "Playing • ",
                                                        fontSize = 11.sp,
                                                        color = BentoPrimary,
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
                                                            onSongClick(index)
                                                        }
                                                    },
                                                    onPlayNext = { onPlayNext(song) },
                                                    onAddToQueue = { /* already in queue */ },
                                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                                    onRemoveFromQueue = {
                                                        if (index in localQueue.indices) {
                                                            localQueue.removeAt(index)
                                                            onRemoveFromQueue(index)
                                                        }
                                                    },
                                                    onMoveToTop = {
                                                        if (index > 0 && index in localQueue.indices) {
                                                            val itm = localQueue.removeAt(index)
                                                            val topTarget = (currentActualIndex + 1).coerceIn(0, localQueue.size)
                                                            localQueue.add(topTarget, itm)
                                                            onUpdateQueue?.invoke(localQueue.map { it.song })
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
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = 16.dp)
                    )
                }
            }
        }
    }
}

