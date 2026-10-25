package com.example.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
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

enum class DragReorderStatus {
    IDLE,
    DRAGGING,
    AUTO_SCROLLING_UP,
    AUTO_SCROLLING_DOWN,
    DROPPING,
    CANCELLED
}

enum class AutoScrollDirection {
    NONE,
    UP,
    DOWN
}

/**
 * High-precision, 1:1 finger-anchored drag-and-drop reorder controller for LazyColumn.
 *
 * Key production fixes:
 * 1. Prevents Jetpack Compose's `LazyListState` key-anchoring jump when swapping at `firstVisibleItemIndex`
 *    during upward drag/auto-scroll by pinning `requestScrollToItem(savedFirstIndex, savedFirstOffset)`.
 * 2. Prevents fast drags outside the visible window from swapping the dragged item out of `visibleItemsInfo`
 *    (which would dispose its `pointerInput` composable mid-gesture) by clamping swaps to the currently
 *    visible item window while `scrollBy` brings off-screen items into view.
 * 3. Commits any completed swaps in `onDragCancel()` so fast-dragging out of the window or sheet boundary
 *    never snaps the song back to its original position.
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

    var draggedKey by mutableStateOf<Any?>(null)
        private set

    // Finger-tracked visual top of the dragged item in viewport coordinates
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
    private var headerOffsetCount: Int = 0
    private var autoScrollJob: Job? = null
    private var edgeZoneHoldStartMs: Long = 0L

    private fun resolveEffectiveStepHeight(): Float {
        val layoutInfo = listState.layoutInfo
        val visible = layoutInfo.visibleItemsInfo
        val matched = draggedKey?.let { k -> visible.firstOrNull { it.key == k } }
        val baseSize = (matched?.size?.toFloat() ?: itemHeightPx).coerceAtLeast(48f)
        val spacing = layoutInfo.mainAxisItemSpacing.toFloat().coerceAtLeast(0f)
        return baseSize + spacing
    }

    /**
     * Computes the currently visible bounds in data-list index space (`minIndex..maxIndex`)
     * so a fast finger fling cannot swap the dragged item completely outside the rendered viewport
     * before `LazyColumn` has scrolled to reveal the target items.
     */
    private fun resolveVisibleDataIndexBounds(minIndex: Int, maxIndex: Int): IntRange {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return minIndex..maxIndex

        val matched = draggedKey?.let { k -> visible.firstOrNull { it.key == k } }
        val curr = currentDraggedIndex
        if (matched != null && curr != null) {
            headerOffsetCount = (matched.index - curr).coerceAtLeast(0)
        }

        val firstVisibleDataIdx = (visible.first().index - headerOffsetCount).coerceIn(minIndex, maxIndex)
        val lastVisibleDataIdx = (visible.last().index - headerOffsetCount).coerceIn(minIndex, maxIndex)
        return if (firstVisibleDataIdx <= lastVisibleDataIdx) {
            firstVisibleDataIdx..lastVisibleDataIdx
        } else {
            minIndex..maxIndex
        }
    }

    /**
     * Checks and executes an adjacent swap when the dragged item's visual center crosses
     * the neighbor item's center. Also pins `firstVisibleItemIndex` when a swap touches the
     * first visible item in `LazyColumn` so upward dragging/auto-scrolling never jumps.
     */
    fun checkAndPerformSwap(minIndex: Int = minItemIndex, maxIndex: Int = maxItemIndex): Boolean {
        val curr = currentDraggedIndex ?: return false
        val stepHeight = resolveEffectiveStepHeight()
        val swapThreshold = stepHeight * 0.5f
        val hysteresis = stepHeight * 0.04f

        val visibleBounds = resolveVisibleDataIndexBounds(minIndex, maxIndex)
        val effectiveMin = visibleBounds.first.coerceAtLeast(minIndex)
        val effectiveMax = visibleBounds.last.coerceAtMost(maxIndex)

        val savedFirstIndex = listState.firstVisibleItemIndex
        val savedFirstOffset = listState.firstVisibleItemScrollOffset

        if (dragOffsetY > (swapThreshold + hysteresis) && curr < effectiveMax) {
            val next = curr + 1
            val currLayoutIndex = curr + headerOffsetCount
            val touchesFirstVisible = currLayoutIndex == savedFirstIndex || (currLayoutIndex - 1) == savedFirstIndex

            currentDraggedIndex = next
            draggedIndex = next
            dragOffsetY -= stepHeight
            try {
                onMove(curr, next)
                if (touchesFirstVisible) {
                    listState.requestScrollToItem(savedFirstIndex, savedFirstOffset)
                }
            } catch (e: Exception) {
                android.util.Log.e("ReorderableLazyList", "Error swapping item from $curr to $next", e)
            }
            return true
        } else if (dragOffsetY < -(swapThreshold + hysteresis) && curr > effectiveMin) {
            val prev = curr - 1
            val prevLayoutIndex = prev + headerOffsetCount
            val touchesFirstVisible = prevLayoutIndex <= savedFirstIndex + 1

            currentDraggedIndex = prev
            draggedIndex = prev
            dragOffsetY += stepHeight
            try {
                onMove(curr, prev)
                if (touchesFirstVisible) {
                    listState.requestScrollToItem(savedFirstIndex, savedFirstOffset)
                }
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

    fun getDraggedItemVisualTop(): Float {
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val matched = draggedKey?.let { k -> visibleItems.firstOrNull { it.key == k } }
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

        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val matchedItem = (key?.let { k -> visibleItems.firstOrNull { it.key == k } })
            ?: visibleItems.firstOrNull { it.index == safeIndex }

        if (matchedItem != null) {
            headerOffsetCount = (matchedItem.index - safeIndex).coerceAtLeast(0)
            draggedItemViewportY = matchedItem.offset.toFloat()
            if (matchedItem.size > 0) {
                itemHeightPx = matchedItem.size.toFloat()
            }
        } else {
            val stepHeight = resolveEffectiveStepHeight()
            val first = visibleItems.firstOrNull()
            draggedItemViewportY = if (first != null) {
                first.offset.toFloat() + (safeIndex - first.index) * stepHeight
            } else {
                layoutInfo.viewportStartOffset.toFloat()
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
        val stepHeight = resolveEffectiveStepHeight()

        draggedItemViewportY += dragAmount
        val newOffset = dragOffsetY + dragAmount

        val boundaryClamp = stepHeight * 0.55f
        val atTopLimit = currIdx <= minIndex && (!listState.canScrollBackward || currIdx == 0)
        val atBottomLimit = currIdx >= maxIndex && !listState.canScrollForward

        if (atTopLimit && newOffset < -boundaryClamp) {
            dragOffsetY = -boundaryClamp
        } else if (atBottomLimit && newOffset > boundaryClamp) {
            dragOffsetY = boundaryClamp
        } else {
            dragOffsetY = newOffset
            val maxAllowedSwaps = 4
            var swaps = 0
            while (checkAndPerformSwap(minIndex, maxIndex) && swaps < maxAllowedSwaps) {
                swaps++
            }
        }

        // Keep draggedItemViewportY synchronized with actual layout offset + dragOffsetY when visible
        val visible = listState.layoutInfo.visibleItemsInfo
        val matched = draggedKey?.let { k -> visible.firstOrNull { it.key == k } }
        if (matched != null) {
            draggedItemViewportY = matched.offset.toFloat() + dragOffsetY
        }
    }

    fun onDrag(dragAmount: Float, maxIndex: Int) {
        onDrag(dragAmount = dragAmount, minIndex = minItemIndex, maxIndex = maxIndex)
    }

    private fun commitPendingReorderIfNeeded() {
        val maxAllowedSwaps = 4
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
    }

    fun onDragEnd(velocity: Float = 0f) {
        isPointerDown = false
        stopAutoScrollLoop()
        status = DragReorderStatus.DROPPING

        commitPendingReorderIfNeeded()

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

    /**
     * When a fast drag reaches outside the visible window or gets cancelled by pointer disposal,
     * we STILL commit the new position (`commitPendingReorderIfNeeded()`) instead of discarding it,
     * so the dragged song stays at the position the user dragged it to rather than snapping back.
     */
    fun onDragCancel() {
        isPointerDown = false
        stopAutoScrollLoop()
        status = DragReorderStatus.CANCELLED

        commitPendingReorderIfNeeded()

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

                    if (visibleItems.isNotEmpty() && totalItems > 1) {
                        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
                        val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
                        val viewportHeight = viewportEnd - viewportStart

                        if (viewportHeight > 0f && (listState.canScrollForward || listState.canScrollBackward)) {
                            val stepHeight = resolveEffectiveStepHeight()
                            // Responsive 26% edge zones for both top (upward) and bottom (downward) auto-scroll
                            val edgeZoneSize = (viewportHeight * 0.26f)
                                .coerceIn(stepHeight * 1.35f, stepHeight * 3.0f)
                                .coerceIn(130f, 300f)
                            val topEdgeEnd = viewportStart + edgeZoneSize
                            val bottomEdgeStart = viewportEnd - edgeZoneSize

                            // Use the minimum of finger-tracked Y and actual rendered visual top for upward trigger
                            // and maximum for downward trigger so fast drags trigger auto-scroll immediately.
                            val visualTop = getDraggedItemVisualTop()
                            val itemTop = minOf(visualTop, draggedItemViewportY)
                            val itemBottom = maxOf(visualTop, draggedItemViewportY) + itemHeightPx.coerceAtLeast(48f)

                            var targetScrollDelta = 0f
                            var currentDirection = AutoScrollDirection.NONE

                            val canScrollUp = currIdx > minItemIndex && listState.canScrollBackward
                            val canScrollDown = currIdx < maxItemIndex && listState.canScrollForward

                            if (itemTop < topEdgeEnd && canScrollUp) {
                                currentDirection = AutoScrollDirection.UP
                                val penetration = (topEdgeEnd - itemTop).coerceAtLeast(0f)
                                val normalized = (penetration / edgeZoneSize).coerceIn(0f, 1f)
                                val curve = normalized * (0.45f + 0.55f * normalized)
                                val minVelocity = 5.0f
                                val maxVelocity = 28.0f
                                var speed = minVelocity + (maxVelocity - minVelocity) * curve

                                if (itemTop < viewportStart) {
                                    val overRatio = ((viewportStart - itemTop) / edgeZoneSize).coerceIn(0f, 1.5f)
                                    speed += overRatio * 10.0f
                                }

                                val now = System.currentTimeMillis()
                                if (edgeZoneHoldStartMs == 0L) edgeZoneHoldStartMs = now
                                val holdMultiplier = 1f + ((now - edgeZoneHoldStartMs) / 900f).coerceIn(0f, 0.55f)

                                speed = (speed * holdMultiplier).coerceIn(minVelocity, 36.0f)
                                targetScrollDelta = -speed
                                autoScrollVelocity = speed
                            } else if (itemBottom > bottomEdgeStart && canScrollDown) {
                                currentDirection = AutoScrollDirection.DOWN
                                val penetration = (itemBottom - bottomEdgeStart).coerceAtLeast(0f)
                                val normalized = (penetration / edgeZoneSize).coerceIn(0f, 1f)
                                val curve = normalized * (0.45f + 0.55f * normalized)
                                val minVelocity = 5.0f
                                val maxVelocity = 28.0f
                                var speed = minVelocity + (maxVelocity - minVelocity) * curve

                                if (itemBottom > viewportEnd) {
                                    val overRatio = ((itemBottom - viewportEnd) / edgeZoneSize).coerceIn(0f, 1.5f)
                                    speed += overRatio * 10.0f
                                }

                                val now = System.currentTimeMillis()
                                if (edgeZoneHoldStartMs == 0L) edgeZoneHoldStartMs = now
                                val holdMultiplier = 1f + ((now - edgeZoneHoldStartMs) / 900f).coerceIn(0f, 0.55f)

                                speed = (speed * holdMultiplier).coerceIn(minVelocity, 36.0f)
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
                                    dragOffsetY += consumed

                                    val boundaryClamp = stepHeight * 0.65f
                                    val latestIdx = currentDraggedIndex ?: currIdx
                                    val atTopLimit = latestIdx <= minItemIndex && !listState.canScrollBackward
                                    val atBottomLimit = latestIdx >= maxItemIndex && !listState.canScrollForward
                                    if (atTopLimit && dragOffsetY < -boundaryClamp) {
                                        dragOffsetY = -boundaryClamp
                                    } else if (atBottomLimit && dragOffsetY > boundaryClamp) {
                                        dragOffsetY = boundaryClamp
                                    }

                                    var swaps = 0
                                    while (checkAndPerformSwap(minItemIndex, maxItemIndex) && swaps < 3) {
                                        swaps++
                                    }
                                } else {
                                    // Even if LazyColumn hit the scroll limit (e.g. header reached top),
                                    // still allow remaining visible items above/below to swap!
                                    var swaps = 0
                                    while (checkAndPerformSwap(minItemIndex, maxItemIndex) && swaps < 3) {
                                        swaps++
                                    }
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

                    try {
                        withFrameNanos { /* VSYNC frame tick */ }
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