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
    headerItemCount: Int = 0,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragEndCallback: (fromIndex: Int, toIndex: Int) -> Unit
) {
    var onMove: (fromIndex: Int, toIndex: Int) -> Unit = onMove
    var onDragEndCallback: (fromIndex: Int, toIndex: Int) -> Unit = onDragEndCallback
    var itemHeightPx: Float = itemHeightPx
    var configuredHeaderCount: Int = headerItemCount.coerceAtLeast(0)

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
    private var headerOffsetCount: Int = headerItemCount.coerceAtLeast(0)
    private var autoScrollJob: Job? = null
    private var edgeZoneHoldStartMs: Long = 0L

    private fun resolveEffectiveStepHeight(): Float {
        val layoutInfo = listState.layoutInfo
        val visible = layoutInfo.visibleItemsInfo
        val nonDragged = draggedKey?.let { k -> visible.firstOrNull { it.key != k && it.index >= configuredHeaderCount } }
        val matched = draggedKey?.let { k -> visible.firstOrNull { it.key == k } }
        val baseSize = (nonDragged?.size?.toFloat() ?: matched?.size?.toFloat() ?: itemHeightPx).coerceAtLeast(48f)
        val spacing = layoutInfo.mainAxisItemSpacing.toFloat().coerceAtLeast(0f)
        return baseSize + spacing
    }

    /**
     * Synchronizes `dragOffsetY` with `draggedItemViewportY - matched.offset` whenever `LazyColumn`
     * has measured the dragged item at its current `currentDraggedIndex`. This eliminates any cumulative
     * drift across long multi-item drags so the dragged card stays 1:1 under the user's finger.
     */
    private fun syncDragOffsetWithMeasuredItem(minIndex: Int = minItemIndex, maxIndex: Int = maxItemIndex) {
        val curr = currentDraggedIndex ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val matched = draggedKey?.let { k -> visible.firstOrNull { it.key == k } } ?: return
        val expectedLayoutIndex = curr + configuredHeaderCount
        if (matched.index == expectedLayoutIndex) {
            val rawOffset = draggedItemViewportY - matched.offset.toFloat()
            val stepHeight = resolveEffectiveStepHeight()
            val boundaryClamp = stepHeight * 0.55f
            val atTopLimit = curr <= minIndex && (!listState.canScrollBackward || curr == 0)
            val atBottomLimit = curr >= maxIndex && !listState.canScrollForward
            dragOffsetY = when {
                atTopLimit && rawOffset < -boundaryClamp -> -boundaryClamp
                atBottomLimit && rawOffset > boundaryClamp -> boundaryClamp
                else -> rawOffset
            }
        }
    }

    /**
     * Computes the currently visible bounds in data-list index space (`minIndex..maxIndex`)
     * so a fast finger fling cannot swap the dragged item completely outside the rendered viewport
     * before `LazyColumn` has scrolled to reveal the target items.
     */
    private fun resolveVisibleDataIndexBounds(minIndex: Int, maxIndex: Int): IntRange {
        headerOffsetCount = configuredHeaderCount
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return minIndex..maxIndex

        val songVisible = visible.filter { it.index >= headerOffsetCount }
        if (songVisible.isEmpty()) return minIndex..maxIndex

        val firstVisibleDataIdx = (songVisible.first().index - headerOffsetCount).coerceIn(minIndex, maxIndex)
        val lastVisibleDataIdx = (songVisible.last().index - headerOffsetCount).coerceIn(minIndex, maxIndex)
        return if (firstVisibleDataIdx <= lastVisibleDataIdx) {
            firstVisibleDataIdx..lastVisibleDataIdx
        } else {
            minIndex..maxIndex
        }
    }

    /**
     * Checks and executes an adjacent swap when the dragged item's visual center crosses
     * the neighbor item's center. Flushes `Snapshot.sendApplyNotifications()` before pinning
     * `firstVisibleItemIndex` when a swap involves `savedFirstIndex` so Compose's
     * `LazyListScrollPosition` never jumps to follow the old first item down the list.
     */
    fun checkAndPerformSwap(minIndex: Int = minItemIndex, maxIndex: Int = maxItemIndex): Boolean {
        val curr = currentDraggedIndex ?: return false
        headerOffsetCount = configuredHeaderCount
        val layoutInfo = listState.layoutInfo
        val visible = layoutInfo.visibleItemsInfo
        val spacing = layoutInfo.mainAxisItemSpacing.toFloat().coerceAtLeast(0f)
        val defaultStepHeight = resolveEffectiveStepHeight()

        val visibleBounds = resolveVisibleDataIndexBounds(minIndex, maxIndex)
        val effectiveMin = visibleBounds.first.coerceAtLeast(minIndex)
        val effectiveMax = visibleBounds.last.coerceAtMost(maxIndex)

        val savedFirstIndex = listState.firstVisibleItemIndex
        val savedFirstOffset = listState.firstVisibleItemScrollOffset

        val currLayoutIndex = curr + headerOffsetCount
        val next = curr + 1
        val nextLayoutIndex = next + headerOffsetCount
        val nextVisibleItem = visible.firstOrNull { it.index == nextLayoutIndex }
        val stepDown = if (nextVisibleItem != null && nextVisibleItem.size > 0) {
            nextVisibleItem.size.toFloat() + spacing
        } else {
            defaultStepHeight
        }
        val downThreshold = stepDown * 0.52f

        if (dragOffsetY > downThreshold && curr < effectiveMax) {
            val touchesFirstVisible = currLayoutIndex == savedFirstIndex || nextLayoutIndex == savedFirstIndex

            currentDraggedIndex = next
            draggedIndex = next
            dragOffsetY -= stepDown
            try {
                onMove(curr, next)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                if (touchesFirstVisible) {
                    listState.requestScrollToItem(savedFirstIndex, savedFirstOffset)
                }
            } catch (e: Exception) {
                android.util.Log.e("ReorderableLazyList", "Error swapping item from $curr to $next", e)
            }
            syncDragOffsetWithMeasuredItem(minIndex, maxIndex)
            return true
        }

        val prev = curr - 1
        val prevLayoutIndex = prev + headerOffsetCount
        val prevVisibleItem = visible.firstOrNull { it.index == prevLayoutIndex }
        val stepUp = if (prevVisibleItem != null && prevVisibleItem.size > 0) {
            prevVisibleItem.size.toFloat() + spacing
        } else {
            defaultStepHeight
        }
        val upThreshold = stepUp * 0.52f

        if (dragOffsetY < -upThreshold && curr > effectiveMin) {
            val touchesFirstVisible = currLayoutIndex == savedFirstIndex || prevLayoutIndex == savedFirstIndex

            currentDraggedIndex = prev
            draggedIndex = prev
            dragOffsetY += stepUp
            try {
                onMove(curr, prev)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                if (touchesFirstVisible) {
                    listState.requestScrollToItem(savedFirstIndex, savedFirstOffset)
                }
            } catch (e: Exception) {
                android.util.Log.e("ReorderableLazyList", "Error swapping item from $curr to $prev", e)
            }
            syncDragOffsetWithMeasuredItem(minIndex, maxIndex)
            return true
        }
        return false
    }

    fun checkAndPerformSwap(maxIndex: Int): Boolean {
        return checkAndPerformSwap(minIndex = minItemIndex, maxIndex = maxIndex)
    }

    fun getDraggedItemVisualTop(): Float {
        return draggedItemViewportY
    }

    fun onDragStart(index: Int, minIndex: Int = 0, maxIndex: Int, key: Any? = null) {
        if (maxIndex < 0 || index !in minIndex..maxIndex) return
        stopAutoScrollLoop()

        val safeIndex = index.coerceIn(minIndex, maxIndex)
        headerOffsetCount = configuredHeaderCount
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
        val expectedLayoutIdx = safeIndex + headerOffsetCount
        val matchedItem = (key?.let { k -> visibleItems.firstOrNull { it.key == k } })
            ?: visibleItems.firstOrNull { it.index == expectedLayoutIdx }

        if (matchedItem != null) {
            draggedItemViewportY = matchedItem.offset.toFloat()
            if (matchedItem.size > 0) {
                itemHeightPx = matchedItem.size.toFloat()
            }
        } else {
            val stepHeight = resolveEffectiveStepHeight()
            val first = visibleItems.firstOrNull()
            draggedItemViewportY = if (first != null) {
                first.offset.toFloat() + (expectedLayoutIdx - first.index) * stepHeight
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

        val layoutInfo = listState.layoutInfo
        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
        draggedItemViewportY = if (viewportEnd > viewportStart) {
            (draggedItemViewportY + dragAmount).coerceIn(
                viewportStart - stepHeight * 0.75f,
                viewportEnd - itemHeightPx.coerceAtLeast(48f) * 0.25f
            )
        } else {
            draggedItemViewportY + dragAmount
        }

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
            syncDragOffsetWithMeasuredItem(minIndex, maxIndex)
            val maxAllowedSwaps = 4
            var swaps = 0
            while (checkAndPerformSwap(minIndex, maxIndex) && swaps < maxAllowedSwaps) {
                swaps++
            }
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
            var lastFrameTimeNanos = 0L
            try {
                while (isActive && isDragging && isPointerDown && !isSettling) {
                    val currIdx = currentDraggedIndex ?: break
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val totalItems = layoutInfo.totalItemsCount

                    var dtScale = 1.0f
                    val nowNanos = System.nanoTime()
                    if (lastFrameTimeNanos != 0L) {
                        dtScale = ((nowNanos - lastFrameTimeNanos) / 16_666_667f).coerceIn(0.5f, 2.2f)
                    }
                    lastFrameTimeNanos = nowNanos

                    if (visibleItems.isNotEmpty() && totalItems > 1) {
                        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
                        val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
                        val viewportHeight = viewportEnd - viewportStart

                        if (viewportHeight > 0f && (listState.canScrollForward || listState.canScrollBackward)) {
                            val stepHeight = resolveEffectiveStepHeight()
                            val edgeZoneSize = (viewportHeight * 0.26f)
                                .coerceIn(stepHeight * 1.35f, stepHeight * 3.0f)
                                .coerceIn(130f, 300f)
                            val topEdgeEnd = viewportStart + edgeZoneSize
                            val bottomEdgeStart = viewportEnd - edgeZoneSize

                            // Strictly use finger-anchored draggedItemViewportY so edge penetration
                            // never drifts or decays over long multi-item position changes.
                            val itemHeight = itemHeightPx.coerceAtLeast(48f)
                            val itemTop = draggedItemViewportY
                            val itemBottom = draggedItemViewportY + itemHeight
                            val itemCenter = draggedItemViewportY + itemHeight * 0.5f
                            val viewportMid = viewportStart + viewportHeight * 0.5f

                            var targetScrollDelta = 0f
                            var currentDirection = AutoScrollDirection.NONE

                            val canScrollUp = currIdx > minItemIndex && listState.canScrollBackward
                            val canScrollDown = currIdx < maxItemIndex && listState.canScrollForward

                            if (itemBottom > bottomEdgeStart && itemCenter >= viewportMid && canScrollDown) {
                                currentDirection = AutoScrollDirection.DOWN
                                val penetration = (itemBottom - bottomEdgeStart).coerceAtLeast(0f)
                                val normalized = (penetration / edgeZoneSize).coerceIn(0f, 1f)
                                val curve = normalized * (0.45f + 0.55f * normalized)
                                val minVelocity = 7.0f
                                val maxVelocity = 30.0f
                                var speed = minVelocity + (maxVelocity - minVelocity) * curve

                                if (itemBottom > viewportEnd) {
                                    val overRatio = ((itemBottom - viewportEnd) / edgeZoneSize).coerceIn(0f, 1.5f)
                                    speed += overRatio * 12.0f
                                }

                                val now = System.currentTimeMillis()
                                if (edgeZoneHoldStartMs == 0L) edgeZoneHoldStartMs = now
                                val holdMultiplier = 1f + ((now - edgeZoneHoldStartMs) / 800f).coerceIn(0f, 0.65f)

                                speed = (speed * holdMultiplier).coerceIn(minVelocity, 40.0f)
                                targetScrollDelta = speed * dtScale
                                autoScrollVelocity = speed
                            } else if (itemTop < topEdgeEnd && itemCenter < viewportMid && canScrollUp) {
                                currentDirection = AutoScrollDirection.UP
                                val penetration = (topEdgeEnd - itemTop).coerceAtLeast(0f)
                                val normalized = (penetration / edgeZoneSize).coerceIn(0f, 1f)
                                val curve = normalized * (0.45f + 0.55f * normalized)
                                val minVelocity = 7.0f
                                val maxVelocity = 30.0f
                                var speed = minVelocity + (maxVelocity - minVelocity) * curve

                                if (itemTop < viewportStart) {
                                    val overRatio = ((viewportStart - itemTop) / edgeZoneSize).coerceIn(0f, 1.5f)
                                    speed += overRatio * 12.0f
                                }

                                val now = System.currentTimeMillis()
                                if (edgeZoneHoldStartMs == 0L) edgeZoneHoldStartMs = now
                                val holdMultiplier = 1f + ((now - edgeZoneHoldStartMs) / 800f).coerceIn(0f, 0.65f)

                                speed = (speed * holdMultiplier).coerceIn(minVelocity, 40.0f)
                                targetScrollDelta = -speed * dtScale
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
                                    syncDragOffsetWithMeasuredItem(minItemIndex, maxItemIndex)

                                    var swaps = 0
                                    while (checkAndPerformSwap(minItemIndex, maxItemIndex) && swaps < 4) {
                                        swaps++
                                    }
                                    syncDragOffsetWithMeasuredItem(minItemIndex, maxItemIndex)
                                } else {
                                    // Even if LazyColumn hit the scroll limit (e.g. header reached top),
                                    // still allow remaining visible items above/below to swap!
                                    var swaps = 0
                                    while (checkAndPerformSwap(minItemIndex, maxItemIndex) && swaps < 4) {
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
    headerItemCount: Int = 0,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragEnd: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> }
): ReorderableLazyListState {
    val scope = rememberCoroutineScope()
    val state = remember(listState) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = itemHeightPx,
            headerItemCount = headerItemCount,
            onMove = onMove,
            onDragEndCallback = onDragEnd
        )
    }
    state.itemHeightPx = itemHeightPx
    state.configuredHeaderCount = headerItemCount.coerceAtLeast(0)
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