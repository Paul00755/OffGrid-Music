package com.example.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State lifecycle representing the active phase of a drag-and-drop reorder operation.
 */
enum class DragReorderStatus {
    IDLE,
    DRAGGING,
    AUTO_SCROLLING_UP,
    AUTO_SCROLLING_DOWN,
    DROPPING,
    CANCELLED
}

/**
 * Active direction of edge-proximity auto-scrolling. Exactly one direction can be active at a time.
 */
enum class AutoScrollDirection {
    NONE,
    UP,
    DOWN
}

/**
 * High-performance, native music player reordering controller with adaptive edge auto-scroll.
 *
 * Core Directives:
 * - Direct finger tracking: Touch deltas translate 1:1 to dragged item offset with zero lag.
 * - Zero spring/bounce on drop: Instant deterministic drop positioning with no delayed snap-back.
 * - Dynamic visual anchoring during auto-scroll: The list moves under the dragged item while
 *   the item stays physically anchored to the user's finger.
 * - Anti-lag boundary clamping: Bounds offset to prevent dead-zone accumulation when hitting limits,
 *   so reversing direction (e.g. suddenly bringing an item from top edge down) swaps and tracks immediately.
 * - Symmetrical adaptive edge zones with continuous quadratic acceleration (no speed steps).
 * - Immediate directional cancellation: Stops auto-scroll instantaneously upon exiting edge zones or on release.
 * - Header-safe auto-scrolling: Allows auto-scrolling to the top of lists with large headers.
 */
class ReorderableLazyListState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    itemHeightPx: Float,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragEndCallback: (fromIndex: Int, toIndex: Int) -> Unit
) {
    var onMove: (fromIndex: Int, toIndex: Int) -> Unit = onMove
    var onDragEndCallback: (fromIndex: Int, toIndex: Int) -> Unit = onDragEndCallback
    var itemHeightPx: Float = itemHeightPx

    var draggedIndex by mutableStateOf<Int?>(null)
        private set

    var isSettling by mutableStateOf(false)
        private set

    var isPointerDown by mutableStateOf(false)
        private set

    val isDragging: Boolean
        get() = draggedIndex != null

    var dragOffsetY by mutableFloatStateOf(0f)
        private set

    var draggedKey: Any? = null
        private set

    // Visual top position of the dragged item in viewport coordinates
    var draggedItemViewportY: Float = 0f
        private set

    var status by mutableStateOf(DragReorderStatus.IDLE)
        private set

    var autoScrollDirection by mutableStateOf(AutoScrollDirection.NONE)
        private set

    var autoScrollVelocity by mutableFloatStateOf(0f)
        private set

    private var initialDraggedIndex: Int? = null
    private var currentDraggedIndex: Int? = null
    var minItemIndex: Int = 0
        private set
    var maxItemIndex: Int = 0
        private set
    private var autoScrollJob: Job? = null
    private var edgeZoneHoldStartMs: Long = 0L

    /**
     * Checks and executes an adjacent transposition between the dragged item and its direct neighbor
     * when the drag offset crosses the half-item boundary with a hysteresis margin to prevent jitter/oscillation.
     * Returns true if a swap was executed, false otherwise.
     */
    fun checkAndPerformSwap(minIndex: Int = minItemIndex, maxIndex: Int = maxItemIndex): Boolean {
        val height = if (itemHeightPx > 0f) itemHeightPx else 160f
        val swapThreshold = height * 0.5f
        // 6% height hysteresis band to prevent boundary oscillation
        val hysteresis = height * 0.06f
        val curr = currentDraggedIndex ?: return false

        if (dragOffsetY > (swapThreshold + hysteresis) && curr < maxIndex) {
            val next = curr + 1
            currentDraggedIndex = next
            draggedIndex = next
            dragOffsetY -= height
            try {
                onMove(curr, next)
            } catch (e: Exception) {
                android.util.Log.e("ReorderableLazyList", "Error swapping item from $curr to $next", e)
            }
            return true
        } else if (dragOffsetY < -(swapThreshold + hysteresis) && curr > minIndex) {
            val prev = curr - 1
            currentDraggedIndex = prev
            draggedIndex = prev
            dragOffsetY += height
            try {
                onMove(curr, prev)
            } catch (e: Exception) {
                android.util.Log.e("ReorderableLazyList", "Error swapping item from $curr to $prev", e)
            }
            return true
        }
        return false
    }

    fun checkAndPerformSwap(maxIndex: Int): Boolean {
        return checkAndPerformSwap(minIndex = minItemIndex, maxIndex = maxIndex)
    }

    /**
     * Computes the current ground-truth visual top of the dragged item in viewport coordinates.
     */
    fun getDraggedItemVisualTop(): Float {
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val matched = (draggedKey?.let { k -> visibleItems.firstOrNull { it.key == k } })
            ?: currentDraggedIndex?.let { idx -> visibleItems.firstOrNull { it.index == idx } }
        return if (matched != null) {
            matched.offset.toFloat() + dragOffsetY
        } else {
            draggedItemViewportY
        }
    }

    fun onDragStart(index: Int, minIndex: Int = 0, maxIndex: Int, key: Any? = null) {
        if (maxIndex < 0 || index !in minIndex..maxIndex) return
        stopAutoScrollLoop()

        val safeIndex = index.coerceIn(minIndex, maxIndex)
        initialDraggedIndex = safeIndex
        currentDraggedIndex = safeIndex
        draggedIndex = safeIndex
        draggedKey = key
        minItemIndex = minIndex
        maxItemIndex = maxIndex
        isSettling = false
        isPointerDown = true
        dragOffsetY = 0f
        edgeZoneHoldStartMs = 0L
        status = DragReorderStatus.DRAGGING
        autoScrollDirection = AutoScrollDirection.NONE
        autoScrollVelocity = 0f

        // Resolve initial viewport position of the dragged item
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val matchedItem = (key?.let { k -> visibleItems.firstOrNull { it.key == k } })
            ?: visibleItems.firstOrNull { it.index == safeIndex }

        val height = if (itemHeightPx > 0f) itemHeightPx else 160f
        if (matchedItem != null) {
            draggedItemViewportY = matchedItem.offset.toFloat()
            if (matchedItem.size > 0) {
                itemHeightPx = matchedItem.size.toFloat()
            }
        } else {
            val first = visibleItems.firstOrNull()
            if (first != null) {
                draggedItemViewportY = first.offset.toFloat() + (safeIndex - first.index) * height
            } else {
                draggedItemViewportY = layoutInfo.viewportStartOffset.toFloat()
            }
        }

        startAutoScrollLoop()
    }

    fun onDragStart(index: Int, maxIndex: Int, key: Any? = null) {
        onDragStart(index = index, minIndex = 0, maxIndex = maxIndex, key = key)
    }

    fun onDrag(dragAmount: Float, minIndex: Int = minItemIndex, maxIndex: Int = maxItemIndex) {
        minItemIndex = minIndex
        maxItemIndex = maxIndex
        val currIdx = currentDraggedIndex ?: return
        val height = if (itemHeightPx > 0f) itemHeightPx else 160f

        // Direct positional finger tracking
        draggedItemViewportY += dragAmount
        val newOffset = dragOffsetY + dragAmount

        // Anti-lag boundary clamping:
        // Only clamp offset into dead-zone if at list boundary AND list cannot scroll in that direction.
        // If the list can still scroll backward (e.g. past headers in Playlist detail or Up Next),
        // let the offset adjust naturally so the dragged item stays visually anchored under the finger.
        val boundaryClamp = height * 0.58f
        val atTopLimit = currIdx <= minIndex && !listState.canScrollBackward
        val atBottomLimit = currIdx >= maxIndex && !listState.canScrollForward

        if (atTopLimit && newOffset < -boundaryClamp) {
            dragOffsetY = -boundaryClamp
        } else if (atBottomLimit && newOffset > boundaryClamp) {
            dragOffsetY = boundaryClamp
        } else {
            dragOffsetY = newOffset
            // Multi-slot swap handling for rapid drags without dropping intermediate positions
            val maxAllowedSwaps = (maxIndex - minIndex + 1).coerceAtLeast(1)
            var swaps = 0
            while (checkAndPerformSwap(minIndex, maxIndex) && swaps < maxAllowedSwaps) {
                swaps++
            }
        }
    }

    fun onDrag(dragAmount: Float, maxIndex: Int) {
        onDrag(dragAmount = dragAmount, minIndex = minItemIndex, maxIndex = maxIndex)
    }

    /**
     * Commits the reorder immediately upon finger release.
     * Zero-delay drop: no spring, no bounce, no snap-back lag.
     */
    fun onDragEnd(velocity: Float = 0f) {
        isPointerDown = false
        stopAutoScrollLoop()
        status = DragReorderStatus.DROPPING

        // Check if any final boundary swap needs to be completed
        val maxAllowedSwaps = (maxItemIndex - minItemIndex + 1).coerceAtLeast(1)
        var swaps = 0
        while (checkAndPerformSwap(minItemIndex, maxItemIndex) && swaps < maxAllowedSwaps) {
            swaps++
        }

        val from = initialDraggedIndex
        val to = currentDraggedIndex

        if (from != null && to != null && from != to) {
            try {
                onDragEndCallback(from, to)
            } catch (e: Exception) {
                android.util.Log.e("ReorderableLazyList", "Error in onDragEndCallback", e)
            }
        }

        // Direct instantaneous state cleanup with zero post-drop lag
        dragOffsetY = 0f
        draggedIndex = null
        initialDraggedIndex = null
        currentDraggedIndex = null
        draggedKey = null
        isSettling = false
        status = DragReorderStatus.IDLE
        autoScrollDirection = AutoScrollDirection.NONE
        autoScrollVelocity = 0f
        edgeZoneHoldStartMs = 0L
    }

    fun onDragCancel() {
        isPointerDown = false
        stopAutoScrollLoop()
        status = DragReorderStatus.CANCELLED

        dragOffsetY = 0f
        draggedIndex = null
        initialDraggedIndex = null
        currentDraggedIndex = null
        draggedKey = null
        isSettling = false
        status = DragReorderStatus.IDLE
        autoScrollDirection = AutoScrollDirection.NONE
        autoScrollVelocity = 0f
        edgeZoneHoldStartMs = 0L
    }

    fun cleanup() {
        isPointerDown = false
        stopAutoScrollLoop()
        draggedIndex = null
        initialDraggedIndex = null
        currentDraggedIndex = null
        draggedKey = null
        dragOffsetY = 0f
        isSettling = false
        edgeZoneHoldStartMs = 0L
        status = DragReorderStatus.IDLE
        autoScrollDirection = AutoScrollDirection.NONE
        autoScrollVelocity = 0f
    }

    private fun startAutoScrollLoop() {
        stopAutoScrollLoop()
        autoScrollJob = scope.launch {
            try {
                while (isActive && isDragging && isPointerDown && !isSettling) {
                    val currIdx = currentDraggedIndex ?: break
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val totalItems = layoutInfo.totalItemsCount

                    // Auto-scroll activates ONLY if content actually exceeds the viewport
                    if (visibleItems.isNotEmpty() && totalItems > 1) {
                        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
                        val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
                        val viewportHeight = viewportEnd - viewportStart

                        if (viewportHeight > 0f && (listState.canScrollForward || listState.canScrollBackward)) {
                            val height = if (itemHeightPx > 0f) itemHeightPx else 160f
                            // Symmetrical adaptive edge zone size: ~18% of viewport height
                            val edgeZoneSize = (viewportHeight * 0.18f).coerceIn(height * 1.0f, height * 2.2f).coerceIn(100f, 240f)
                            val topEdgeEnd = viewportStart + edgeZoneSize
                            val bottomEdgeStart = viewportEnd - edgeZoneSize

                            // Calculate actual visual coordinates based on continuous finger-tracked position
                            val itemTop = draggedItemViewportY
                            val itemBottom = itemTop + height

                            var targetScrollDelta = 0f
                            var currentDirection = AutoScrollDirection.NONE

                            // Directional boundaries:
                            // Upward scrolling continues if list can scroll backward AND (item not at minItemIndex OR list is scrolled down)
                            val canScrollUp = listState.canScrollBackward && (currIdx > minItemIndex || listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
                            // Downward scrolling continues if list can scroll forward
                            val canScrollDown = listState.canScrollForward && (currIdx < maxItemIndex || listState.canScrollForward)

                            if (itemTop < topEdgeEnd && canScrollUp) {
                                currentDirection = AutoScrollDirection.UP
                                val penetration = (topEdgeEnd - itemTop).coerceAtLeast(0f)
                                val normalized = (penetration / edgeZoneSize).coerceIn(0f, 1f)

                                // Smooth monotonic progressive velocity curve (quadratic easing)
                                val curve = normalized * normalized
                                val minVelocity = 2.5f
                                val maxVelocity = 22.0f
                                var speed = minVelocity + (maxVelocity - minVelocity) * curve

                                // Smooth progressive acceleration when dragged beyond top boundary
                                if (itemTop < viewportStart) {
                                    val overRatio = ((viewportStart - itemTop) / edgeZoneSize).coerceIn(0f, 1.2f)
                                    speed += overRatio * 5.0f
                                }

                                val now = System.currentTimeMillis()
                                if (edgeZoneHoldStartMs == 0L) edgeZoneHoldStartMs = now
                                val holdMultiplier = 1f + ((now - edgeZoneHoldStartMs) / 1000f).coerceIn(0f, 0.45f)

                                speed = (speed * holdMultiplier).coerceIn(minVelocity, 26.0f)
                                targetScrollDelta = -speed
                                autoScrollVelocity = speed
                            } else if (itemBottom > bottomEdgeStart && canScrollDown) {
                                currentDirection = AutoScrollDirection.DOWN
                                val penetration = (itemBottom - bottomEdgeStart).coerceAtLeast(0f)
                                val normalized = (penetration / edgeZoneSize).coerceIn(0f, 1f)

                                // Smooth monotonic progressive velocity curve (quadratic easing)
                                val curve = normalized * normalized
                                val minVelocity = 2.5f
                                val maxVelocity = 22.0f
                                var speed = minVelocity + (maxVelocity - minVelocity) * curve

                                // Smooth progressive acceleration when dragged beyond bottom boundary
                                if (itemBottom > viewportEnd) {
                                    val overRatio = ((itemBottom - viewportEnd) / edgeZoneSize).coerceIn(0f, 1.2f)
                                    speed += overRatio * 5.0f
                                }

                                val now = System.currentTimeMillis()
                                if (edgeZoneHoldStartMs == 0L) edgeZoneHoldStartMs = now
                                val holdMultiplier = 1f + ((now - edgeZoneHoldStartMs) / 1000f).coerceIn(0f, 0.45f)

                                speed = (speed * holdMultiplier).coerceIn(minVelocity, 26.0f)
                                targetScrollDelta = speed
                                autoScrollVelocity = speed
                            } else {
                                edgeZoneHoldStartMs = 0L
                                autoScrollVelocity = 0f
                            }

                            autoScrollDirection = currentDirection
                            status = when (currentDirection) {
                                AutoScrollDirection.UP -> DragReorderStatus.AUTO_SCROLLING_UP
                                AutoScrollDirection.DOWN -> DragReorderStatus.AUTO_SCROLLING_DOWN
                                AutoScrollDirection.NONE -> DragReorderStatus.DRAGGING
                            }

                            if (targetScrollDelta != 0f) {
                                val consumed = listState.scrollBy(targetScrollDelta)
                                if (consumed != 0f) {
                                    // Rock-solid stability: Keep the dragged item visually anchored under finger while list scrolls
                                    dragOffsetY += consumed

                                    // Clamp dragOffsetY at boundary only if the list cannot scroll in that direction
                                    val boundaryClamp = height * 0.58f
                                    val atTopLimit = currIdx <= minItemIndex && !listState.canScrollBackward
                                    val atBottomLimit = currIdx >= maxItemIndex && !listState.canScrollForward
                                    if (atTopLimit && dragOffsetY < -boundaryClamp) {
                                        dragOffsetY = -boundaryClamp
                                    } else if (atBottomLimit && dragOffsetY > boundaryClamp) {
                                        dragOffsetY = boundaryClamp
                                    }

                                    val maxAllowedSwaps = (maxItemIndex - minItemIndex + 1).coerceAtLeast(1)
                                    var swaps = 0
                                    while (checkAndPerformSwap(minItemIndex, maxItemIndex) && swaps < maxAllowedSwaps) {
                                        swaps++
                                    }
                                } else {
                                    autoScrollVelocity = 0f
                                }
                            }
                        } else {
                            edgeZoneHoldStartMs = 0L
                            autoScrollDirection = AutoScrollDirection.NONE
                            autoScrollVelocity = 0f
                            status = DragReorderStatus.DRAGGING
                        }
                    }

                    // Vsync frame synchronization
                    try {
                        withFrameNanos { /* frame tick */ }
                    } catch (_: Exception) {
                        delay(16L)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("ReorderableLazyList", "Error in auto-scroll loop", e)
            } finally {
                edgeZoneHoldStartMs = 0L
                autoScrollDirection = AutoScrollDirection.NONE
                autoScrollVelocity = 0f
                if (status != DragReorderStatus.DROPPING && status != DragReorderStatus.CANCELLED) {
                    status = DragReorderStatus.IDLE
                }
                stopAutoScrollLoop()
            }
        }
    }

    private fun stopAutoScrollLoop() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }
}

@Composable
fun rememberReorderableLazyListState(
    listState: LazyListState,
    itemHeightPx: Float,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragEnd: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> }
): ReorderableLazyListState {
    val scope = rememberCoroutineScope()
    val state = remember(listState) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = itemHeightPx,
            onMove = onMove,
            onDragEndCallback = onDragEnd
        )
    }
    state.itemHeightPx = itemHeightPx
    state.onMove = onMove
    state.onDragEndCallback = onDragEnd

    DisposableEffect(state) {
        onDispose {
            state.cleanup()
        }
    }
    return state
}

@Composable
fun Modifier.reorderableDragHandle(
    state: ReorderableLazyListState,
    getIndex: () -> Int,
    getMinIndex: (() -> Int)? = null,
    getMaxIndex: () -> Int,
    getKey: (() -> Any?)? = null,
    onStartFeedback: () -> Unit = {},
    onEndFeedback: () -> Unit = {}
): Modifier {
    val currentGetIndex by rememberUpdatedState(getIndex)
    val currentGetMinIndex by rememberUpdatedState(getMinIndex)
    val currentGetMaxIndex by rememberUpdatedState(getMaxIndex)
    val currentGetKey by rememberUpdatedState(getKey)
    val currentStartFeedback by rememberUpdatedState(onStartFeedback)
    val currentEndFeedback by rememberUpdatedState(onEndFeedback)

    return this.pointerInput(state) {
        var isThisHandleDragging = false
        try {
            val velocityTracker = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = {
                    velocityTracker.resetTracking()
                    val idx = currentGetIndex()
                    val minIdx = currentGetMinIndex?.invoke() ?: 0
                    val maxIdx = currentGetMaxIndex()
                    val itemKey = currentGetKey?.invoke()
                    isThisHandleDragging = true
                    state.onDragStart(index = idx, minIndex = minIdx, maxIndex = maxIdx, key = itemKey)
                    currentStartFeedback()
                },
                onDragEnd = {
                    val velocity = velocityTracker.calculateVelocity().y
                    isThisHandleDragging = false
                    state.onDragEnd(velocity)
                    currentEndFeedback()
                },
                onDragCancel = {
                    isThisHandleDragging = false
                    state.onDragCancel()
                },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val minIdx = currentGetMinIndex?.invoke() ?: 0
                    state.onDrag(dragAmount = dragAmount, minIndex = minIdx, maxIndex = currentGetMaxIndex())
                }
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                if (isThisHandleDragging) {
                    isThisHandleDragging = false
                    state.onDragCancel()
                }
                throw e
            }
            android.util.Log.e("ReorderableLazyList", "Error in drag handle pointer input", e)
            if (isThisHandleDragging) {
                isThisHandleDragging = false
                state.onDragCancel()
            }
        } finally {
            if (isThisHandleDragging) {
                isThisHandleDragging = false
                state.onDragCancel()
            }
        }
    }
}

@Composable
fun Modifier.reorderableDragHandle(
    state: ReorderableLazyListState,
    index: Int,
    maxIndex: Int,
    key: Any? = null,
    onStartFeedback: () -> Unit = {},
    onEndFeedback: () -> Unit = {}
): Modifier = reorderableDragHandle(
    state = state,
    getIndex = { index },
    getMinIndex = { 0 },
    getMaxIndex = { maxIndex },
    getKey = { key },
    onStartFeedback = onStartFeedback,
    onEndFeedback = onEndFeedback
)
