package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.model.SmartPlaylist
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.HapticUtils

/**
 * Smooth finger-anchored drag-and-drop bottom sheet for reordering Smart Playlists themselves.
 * Uses [ReorderableLazyListState] for Up-Next-identical smoothness and automatic edge scrolling.
 * Saves displayOrder persistently across app restarts and cloud backups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderSmartPlaylistsSheet(
    smartPlaylists: List<SmartPlaylist>,
    allSongs: List<Song>,
    onReorderSmartPlaylists: (List<SmartPlaylist>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val localList = remember {
        mutableStateListOf<SmartPlaylist>().apply { addAll(smartPlaylists) }
    }
    var hasMoved by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 74.dp.toPx() }
    val currentOnReorder by rememberUpdatedState(onReorderSmartPlaylists)

    val reorderableState = rememberReorderableLazyListState(
        listState = listState,
        itemHeightPx = itemHeightPx,
        headerItemCount = 0,
        onMove = { from, to ->
            if (from != to && from in localList.indices && to in localList.indices) {
                Snapshot.withMutableSnapshot {
                    if (kotlin.math.abs(from - to) == 1) {
                        val tmp = localList[from]
                        localList[from] = localList[to]
                        localList[to] = tmp
                    } else {
                        val item = localList.removeAt(from)
                        localList.add(to.coerceIn(0, localList.size), item)
                    }
                }
                hasMoved = true
                HapticUtils.performTickHaptic(context)
            }
        },
        onDragEnd = { _, _ ->
            if (hasMoved) {
                currentOnReorder(localList.toList())
                hasMoved = false
            }
        }
    )

    LaunchedEffect(smartPlaylists, reorderableState.isDragging, reorderableState.isSettling) {
        if (!reorderableState.isDragging && !reorderableState.isSettling) {
            val incomingKeys = smartPlaylists.map { "${it.id}_${it.name}" }
            val currentKeys = localList.map { "${it.id}_${it.name}" }
            if (incomingKeys.toSet() != currentKeys.toSet()) {
                localList.clear()
                localList.addAll(smartPlaylists)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (hasMoved && localList.isNotEmpty()) {
                currentOnReorder(localList.toList())
            }
        }
    }

    val trackCountById = remember(smartPlaylists, allSongs) {
        smartPlaylists.associate { it.id to it.filterSongs(allSongs).size }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (hasMoved && localList.isNotEmpty()) {
                currentOnReorder(localList.toList())
                hasMoved = false
            }
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = BentoBackground,
        contentColor = BentoTextPrimary,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = BentoTextSecondary.copy(alpha = 0.4f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reorder Smart Playlists",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Drag handles to arrange · order syncs with cloud backup",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary
                    )
                }
                IconButton(
                    onClick = {
                        if (hasMoved && localList.isNotEmpty()) {
                            currentOnReorder(localList.toList())
                            hasMoved = false
                        }
                        onDismiss()
                    },
                    modifier = Modifier.testTag("reorder_smart_playlists_close_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = BentoTextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .testTag("reorder_smart_playlists_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = localList,
                    key = { _, sp -> "sp_order_${sp.id}_${sp.createdAt}" }
                ) { index, sp ->
                    val stableKey = "sp_order_${sp.id}_${sp.createdAt}"
                    val isDragging = reorderableState.draggedKey == stableKey ||
                        (reorderableState.draggedKey == null && reorderableState.draggedIndex == index)
                    val isAutoScrolling = reorderableState.autoScrollDirection != AutoScrollDirection.NONE
                    val shape = remember { RoundedCornerShape(16.dp) }
                    val matchedCount = trackCountById[sp.id] ?: 0

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
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
                        if (isDragging) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(66.dp),
                                shape = shape,
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
                                .clip(shape)
                                .background(if (isDragging) BentoCardDarkPurple else BentoCardSurface)
                                .border(
                                    width = if (isDragging) 1.5.dp else 1.dp,
                                    color = if (isDragging) BentoPrimary else BentoBorder.copy(alpha = 0.45f),
                                    shape = shape
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("reorder_smart_playlist_handle_${sp.id}")
                                    .reorderableDragHandle(
                                        state = reorderableState,
                                        getIndex = {
                                            val found = localList.indexOfFirst { it.id == sp.id && it.createdAt == sp.createdAt }
                                            if (found >= 0) found else index
                                        },
                                        getMinIndex = { 0 },
                                        getMaxIndex = { (localList.size - 1).coerceAtLeast(0) },
                                        getKey = { stableKey },
                                        onStartFeedback = { HapticUtils.performTickHaptic(context) },
                                        onEndFeedback = { HapticUtils.performClickHaptic(context) }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = "Drag to reorder smart playlist",
                                    tint = if (isDragging) BentoPrimary else BentoTextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BentoPrimary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sp.name,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "$matchedCount tracks · ${sp.getCriteriaBadges().take(2).joinToString(" · ")}",
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = "#${index + 1}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    currentOnReorder(localList.toList())
                    hasMoved = false
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BentoPrimary,
                    contentColor = BentoOnPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .height(48.dp)
                    .testTag("reorder_smart_playlists_done_button")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    }
}
