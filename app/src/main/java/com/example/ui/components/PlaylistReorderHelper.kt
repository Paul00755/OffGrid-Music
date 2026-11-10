package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import com.example.data.model.Playlist
import com.example.util.HapticUtils
import kotlinx.coroutines.delay

/**
 * State controller for smooth hold-and-drag reordering in a LazyColumn (1D vertical list)
 * with continuous top/bottom edge auto-scrolling.
 */
class LazyListPlaylistReorderState(
    private val context: Context,
    val listState: LazyListState,
    private val onCommitOrder: (List<Playlist>) -> Unit
) {
    var orderedPlaylists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    var draggedPlaylistId by mutableStateOf<Long?>(null)
        private set

    var draggedOffsetY by mutableFloatStateOf(0f)
        private set

    var autoScrollSpeedPx by mutableFloatStateOf(0f)
        private set

    fun syncExternalPlaylists(external: List<Playlist>) {
        if (draggedPlaylistId == null) {
            orderedPlaylists = external
        }
    }

    fun onDragStart(playlistId: Long) {
        if (orderedPlaylists.size <= 1) return
        HapticUtils.performClickHaptic(context)
        draggedPlaylistId = playlistId
        draggedOffsetY = 0f
        autoScrollSpeedPx = 0f
    }

    fun onDragEndOrCancel() {
        val wasDragging = draggedPlaylistId != null
        draggedPlaylistId = null
        draggedOffsetY = 0f
        autoScrollSpeedPx = 0f
        if (wasDragging) {
            onCommitOrder(orderedPlaylists)
        }
    }

    fun onDragBy(deltaY: Float) {
        val activeId = draggedPlaylistId ?: return
        draggedOffsetY += deltaY
        checkAndSwapItems(activeId)
        updateAutoScrollSpeed(activeId)
    }

    fun onAutoScrollTick(scrolledPx: Float) {
        val activeId = draggedPlaylistId ?: return
        if (scrolledPx == 0f) return
        // Keep the item visually under the user's finger while the list scrolls underneath
        draggedOffsetY += scrolledPx
        checkAndSwapItems(activeId)
    }

    private fun updateAutoScrollSpeed(activeId: Long) {
        val layoutInfo = listState.layoutInfo
        val currentItem = layoutInfo.visibleItemsInfo.firstOrNull { it.key == activeId } ?: run {
            autoScrollSpeedPx = 0f
            return
        }
        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
        val viewportHeight = (viewportEnd - viewportStart).coerceAtLeast(1f)
        val edgeThreshold = (viewportHeight * 0.18f).coerceIn(110f, 240f)

        val draggedCenterY = currentItem.offset + (currentItem.size / 2f) + draggedOffsetY
        autoScrollSpeedPx = when {
            draggedCenterY < viewportStart + edgeThreshold -> {
                val intensity = ((viewportStart + edgeThreshold - draggedCenterY) / edgeThreshold).coerceIn(0.15f, 1.0f)
                -22f * intensity
            }
            draggedCenterY > viewportEnd - edgeThreshold -> {
                val intensity = ((draggedCenterY - (viewportEnd - edgeThreshold)) / edgeThreshold).coerceIn(0.15f, 1.0f)
                22f * intensity
            }
            else -> 0f
        }
    }

    private fun checkAndSwapItems(activeId: Long) {
        val currentIndex = orderedPlaylists.indexOfFirst { it.id == activeId }
        if (currentIndex == -1) return

        val layoutInfo = listState.layoutInfo
        val currentItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == activeId } ?: return
        val draggedCenterY = currentItemInfo.offset + (currentItemInfo.size / 2f) + draggedOffsetY

        val targetItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
            val targetId = itemInfo.key as? Long ?: return@firstOrNull false
            if (targetId == activeId) return@firstOrNull false
            draggedCenterY >= itemInfo.offset && draggedCenterY <= (itemInfo.offset + itemInfo.size)
        } ?: return

        val targetId = targetItemInfo.key as? Long ?: return
        val targetIndex = orderedPlaylists.indexOfFirst { it.id == targetId }
        if (targetIndex == -1 || targetIndex == currentIndex) return

        val mutable = orderedPlaylists.toMutableList()
        val moved = mutable.removeAt(currentIndex)
        mutable.add(targetIndex, moved)
        orderedPlaylists = mutable

        // Adjust offset by the distance between the two items so the card stays smoothly pinned to the finger
        val offsetCompensation = (targetItemInfo.offset - currentItemInfo.offset).toFloat()
        draggedOffsetY -= offsetCompensation
        HapticUtils.performClickHaptic(context)
        onCommitOrder(orderedPlaylists)
    }
}

@Composable
fun rememberLazyListPlaylistReorderState(
    context: Context,
    playlists: List<Playlist>,
    listState: LazyListState,
    onReorderPlaylists: (List<Playlist>) -> Unit
): LazyListPlaylistReorderState {
    val updatedCallback by rememberUpdatedState(onReorderPlaylists)
    val state = remember(listState) {
        LazyListPlaylistReorderState(
            context = context,
            listState = listState,
            onCommitOrder = { updatedCallback(it) }
        )
    }
    LaunchedEffect(playlists) {
        state.syncExternalPlaylists(playlists)
    }
    LaunchedEffect(state.draggedPlaylistId) {
        if (state.draggedPlaylistId != null) {
            while (state.draggedPlaylistId != null) {
                val speed = state.autoScrollSpeedPx
                if (speed != 0f) {
                    val consumed = state.listState.scrollBy(speed)
                    if (consumed != 0f) {
                        state.onAutoScrollTick(consumed)
                    }
                }
                delay(16L)
            }
        }
    }
    return state
}

fun Modifier.playlistListReorderItem(
    reorderState: LazyListPlaylistReorderState,
    playlistId: Long
): Modifier {
    val isDragged = reorderState.draggedPlaylistId == playlistId
    return this
        .zIndex(if (isDragged) 10f else 0f)
        .graphicsLayer {
            if (isDragged) {
                translationY = reorderState.draggedOffsetY
                scaleX = 1.03f
                scaleY = 1.03f
                shadowElevation = 24f
            }
        }
        .pointerInput(playlistId) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    reorderState.onDragStart(playlistId)
                },
                onDragEnd = {
                    reorderState.onDragEndOrCancel()
                },
                onDragCancel = {
                    reorderState.onDragEndOrCancel()
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    reorderState.onDragBy(dragAmount.y)
                }
            )
        }
}

/**
 * State controller for smooth hold-and-drag reordering in a LazyVerticalGrid (2D Bento Grid)
 * with continuous top/bottom edge auto-scrolling.
 */
class LazyGridPlaylistReorderState(
    private val context: Context,
    val gridState: LazyGridState,
    private val onCommitOrder: (List<Playlist>) -> Unit
) {
    var orderedPlaylists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    var draggedPlaylistId by mutableStateOf<Long?>(null)
        private set

    var draggedOffset by mutableStateOf(Offset.Zero)
        private set

    var autoScrollSpeedPx by mutableFloatStateOf(0f)
        private set

    fun syncExternalPlaylists(external: List<Playlist>) {
        if (draggedPlaylistId == null) {
            orderedPlaylists = external
        }
    }

    fun onDragStart(playlistId: Long) {
        if (orderedPlaylists.size <= 1) return
        HapticUtils.performClickHaptic(context)
        draggedPlaylistId = playlistId
        draggedOffset = Offset.Zero
        autoScrollSpeedPx = 0f
    }

    fun onDragEndOrCancel() {
        val wasDragging = draggedPlaylistId != null
        draggedPlaylistId = null
        draggedOffset = Offset.Zero
        autoScrollSpeedPx = 0f
        if (wasDragging) {
            onCommitOrder(orderedPlaylists)
        }
    }

    fun onDragBy(delta: Offset) {
        val activeId = draggedPlaylistId ?: return
        draggedOffset += delta
        checkAndSwapGridItems(activeId)
        updateAutoScrollSpeed(activeId)
    }

    fun onAutoScrollTick(scrolledPx: Float) {
        val activeId = draggedPlaylistId ?: return
        if (scrolledPx == 0f) return
        draggedOffset = Offset(draggedOffset.x, draggedOffset.y + scrolledPx)
        checkAndSwapGridItems(activeId)
    }

    private fun updateAutoScrollSpeed(activeId: Long) {
        val layoutInfo = gridState.layoutInfo
        val currentItem = layoutInfo.visibleItemsInfo.firstOrNull { it.key == activeId } ?: run {
            autoScrollSpeedPx = 0f
            return
        }
        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
        val viewportHeight = (viewportEnd - viewportStart).coerceAtLeast(1f)
        val edgeThreshold = (viewportHeight * 0.18f).coerceIn(110f, 240f)

        val draggedCenterY = currentItem.offset.y + (currentItem.size.height / 2f) + draggedOffset.y
        autoScrollSpeedPx = when {
            draggedCenterY < viewportStart + edgeThreshold -> {
                val intensity = ((viewportStart + edgeThreshold - draggedCenterY) / edgeThreshold).coerceIn(0.15f, 1.0f)
                -22f * intensity
            }
            draggedCenterY > viewportEnd - edgeThreshold -> {
                val intensity = ((draggedCenterY - (viewportEnd - edgeThreshold)) / edgeThreshold).coerceIn(0.15f, 1.0f)
                22f * intensity
            }
            else -> 0f
        }
    }

    private fun checkAndSwapGridItems(activeId: Long) {
        val currentIndex = orderedPlaylists.indexOfFirst { it.id == activeId }
        if (currentIndex == -1) return

        val layoutInfo = gridState.layoutInfo
        val currentItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == activeId } ?: return
        val draggedCenterX = currentItemInfo.offset.x + (currentItemInfo.size.width / 2f) + draggedOffset.x
        val draggedCenterY = currentItemInfo.offset.y + (currentItemInfo.size.height / 2f) + draggedOffset.y

        val targetItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
            val targetId = itemInfo.key as? Long ?: return@firstOrNull false
            if (targetId == activeId) return@firstOrNull false
            draggedCenterX >= itemInfo.offset.x &&
                draggedCenterX <= (itemInfo.offset.x + itemInfo.size.width) &&
                draggedCenterY >= itemInfo.offset.y &&
                draggedCenterY <= (itemInfo.offset.y + itemInfo.size.height)
        } ?: return

        val targetId = targetItemInfo.key as? Long ?: return
        val targetIndex = orderedPlaylists.indexOfFirst { it.id == targetId }
        if (targetIndex == -1 || targetIndex == currentIndex) return

        val mutable = orderedPlaylists.toMutableList()
        val moved = mutable.removeAt(currentIndex)
        mutable.add(targetIndex, moved)
        orderedPlaylists = mutable

        val compX = (targetItemInfo.offset.x - currentItemInfo.offset.x).toFloat()
        val compY = (targetItemInfo.offset.y - currentItemInfo.offset.y).toFloat()
        draggedOffset = Offset(draggedOffset.x - compX, draggedOffset.y - compY)
        HapticUtils.performClickHaptic(context)
        onCommitOrder(orderedPlaylists)
    }
}

@Composable
fun rememberLazyGridPlaylistReorderState(
    context: Context,
    playlists: List<Playlist>,
    gridState: LazyGridState,
    onReorderPlaylists: (List<Playlist>) -> Unit
): LazyGridPlaylistReorderState {
    val updatedCallback by rememberUpdatedState(onReorderPlaylists)
    val state = remember(gridState) {
        LazyGridPlaylistReorderState(
            context = context,
            gridState = gridState,
            onCommitOrder = { updatedCallback(it) }
        )
    }
    LaunchedEffect(playlists) {
        state.syncExternalPlaylists(playlists)
    }
    LaunchedEffect(state.draggedPlaylistId) {
        if (state.draggedPlaylistId != null) {
            while (state.draggedPlaylistId != null) {
                val speed = state.autoScrollSpeedPx
                if (speed != 0f) {
                    val consumed = state.gridState.scrollBy(speed)
                    if (consumed != 0f) {
                        state.onAutoScrollTick(consumed)
                    }
                }
                delay(16L)
            }
        }
    }
    return state
}

fun Modifier.playlistGridReorderItem(
    reorderState: LazyGridPlaylistReorderState,
    playlistId: Long
): Modifier {
    val isDragged = reorderState.draggedPlaylistId == playlistId
    return this
        .zIndex(if (isDragged) 10f else 0f)
        .graphicsLayer {
            if (isDragged) {
                translationX = reorderState.draggedOffset.x
                translationY = reorderState.draggedOffset.y
                scaleX = 1.04f
                scaleY = 1.04f
                shadowElevation = 28f
            }
        }
        .pointerInput(playlistId) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    reorderState.onDragStart(playlistId)
                },
                onDragEnd = {
                    reorderState.onDragEndOrCancel()
                },
                onDragCancel = {
                    reorderState.onDragEndOrCancel()
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    reorderState.onDragBy(dragAmount)
                }
            )
        }
}
