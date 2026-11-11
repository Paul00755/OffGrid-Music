package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.example.ui.theme.BentoPrimary
import com.example.util.HapticUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * High-responsiveness, zero-lag FastScrollbar for LazyColumn lists.
 *
 * Features:
 * - Instant 1:1 finger tracking with zero coordinate distortion.
 * - Smoothed running average item height estimation so heterogeneous list items (headers, carousels, song rows)
 *   never cause vertical jitter during fast scrubbing.
 * - GPU-accelerated graphicsLayer translation for thumb and floating section indicator to avoid layout-phase
 *   re-measurements on every drag frame.
 * - Deduplicated Main.immediate scroll dispatch for buttery-smooth 120fps scrolling.
 */
@Composable
fun FastScrollbar(
    listState: LazyListState,
    totalItems: Int,
    modifier: Modifier = Modifier,
    headerItemsCount: Int = 0,
    itemIndexToLabel: ((Int) -> String?)? = null,
    itemLabelProvider: ((Int) -> String?)? = null,
    minItemsToShow: Int = 6,
    topPadding: Dp = 8.dp,
    bottomPadding: Dp = 100.dp,
    thumbColor: Color = BentoPrimary
) {
    val resolvedLabelProvider = itemIndexToLabel ?: itemLabelProvider
    val layoutTotalItems by remember {
        derivedStateOf { listState.layoutInfo.totalItemsCount }
    }
    val effectiveTotalItems = if (layoutTotalItems > 0) layoutTotalItems else totalItems
    if (effectiveTotalItems < minItemsToShow) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var lastHapticLabel by remember { mutableStateOf<String?>(null) }

    // Exponentially smoothed item height prevents sudden jumps when headers/cards enter or leave the viewport
    var smoothedItemSizePx by remember { mutableFloatStateOf(with(density) { 64.dp.toPx() }) }
    var lastDispatchedIndex by remember { mutableIntStateOf(-1) }
    var lastDispatchedOffsetPx by remember { mutableIntStateOf(-1) }

    val topPaddingPx = remember(density, topPadding) { with(density) { topPadding.toPx() } }
    val bottomPaddingPx = remember(density, bottomPadding) { with(density) { bottomPadding.toPx() } }
    val thumbHeightPx = remember(density) { with(density) { 52.dp.toPx() } }

    // Continuous sub-item scroll fraction so the thumb glides smoothly pixel-by-pixel
    val stateScrollFraction by remember(listState, totalItems, headerItemsCount) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalCount = if (layoutInfo.totalItemsCount > 0) layoutInfo.totalItemsCount else totalItems
            val visibleItems = layoutInfo.visibleItemsInfo
            val visibleCount = visibleItems.size
            if (totalCount <= 1 || visibleCount == 0) {
                0f
            } else {
                val scrollableItems = (totalCount - visibleCount).coerceAtLeast(1)
                val firstItemSize = visibleItems.firstOrNull()?.size ?: 0
                val subItemFraction = if (firstItemSize > 0) {
                    (listState.firstVisibleItemScrollOffset.toFloat() / firstItemSize.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val exactIndex = listState.firstVisibleItemIndex.toFloat() + subItemFraction
                (exactIndex / scrollableItems.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    val currentItemIndex by remember(listState, totalItems, headerItemsCount, isDragging) {
        derivedStateOf {
            if (!isDragging) {
                0
            } else {
                val layoutInfo = listState.layoutInfo
                val totalCount = if (layoutInfo.totalItemsCount > 0) layoutInfo.totalItemsCount else totalItems
                val safeHeader = headerItemsCount.coerceIn(0, (totalCount - 1).coerceAtLeast(0))
                val contentCount = (totalCount - safeHeader).coerceAtLeast(1)
                (safeHeader + (dragFraction * (contentCount - 1).coerceAtLeast(0)).roundToInt())
                    .coerceIn(safeHeader, (totalCount - 1).coerceAtLeast(0))
            }
        }
    }

    val latestItemIndexToLabel by rememberUpdatedState(resolvedLabelProvider)
    val latestTotalItems by rememberUpdatedState(totalItems)
    val latestHeaderCount by rememberUpdatedState(headerItemsCount)

    // Helper that immediately scrolls the LazyListState to an exact fractional position (item + pixel offset)
    var activeScrollJob by remember { mutableStateOf<Job?>(null) }
    val scrollToFractionImmediate: (Float) -> Unit = remember(listState, coroutineScope) {
        { targetFraction: Float ->
            val clamped = targetFraction.coerceIn(0f, 1f)
            dragFraction = clamped

            val layoutInfo = listState.layoutInfo
            val totalCount = if (layoutInfo.totalItemsCount > 0) layoutInfo.totalItemsCount else latestTotalItems
            val visibleItems = layoutInfo.visibleItemsInfo
            val visibleCount = visibleItems.size.coerceAtLeast(1)
            val maxItemIndex = (totalCount - 1).coerceAtLeast(0)
            val safeHeader = latestHeaderCount.coerceIn(0, maxItemIndex)

            val contentVisible = visibleItems.filter { it.index >= safeHeader }
            val sampleVisible = contentVisible.ifEmpty { visibleItems }
            if (sampleVisible.isNotEmpty()) {
                var sum = 0
                for (i in sampleVisible.indices) sum += sampleVisible[i].size
                val observedAvg = (sum.toFloat() / sampleVisible.size.toFloat()).coerceAtLeast(24f)
                smoothedItemSizePx = (smoothedItemSizePx * 0.80f) + (observedAvg * 0.20f)
            }

            val finalIndex: Int
            val targetOffsetPx: Int

            if (clamped <= 0.004f) {
                finalIndex = 0
                targetOffsetPx = 0
            } else if (safeHeader > 0 && totalCount > safeHeader) {
                val contentCount = (totalCount - safeHeader).coerceAtLeast(1)
                val maxContentScroll = (contentCount - (visibleCount - 1).coerceAtLeast(1)).coerceAtLeast(1)
                val exactContentPos = clamped * maxContentScroll.toFloat()
                val songIdx = exactContentPos.toInt().coerceIn(0, contentCount - 1)
                val remainder = (exactContentPos - songIdx).coerceIn(0f, 0.999f)
                finalIndex = if (clamped >= 0.998f) maxItemIndex else (safeHeader + songIdx).coerceIn(0, maxItemIndex)
                targetOffsetPx = if (clamped >= 0.998f) 0 else (remainder * smoothedItemSizePx).roundToInt().coerceAtLeast(0)
            } else {
                val maxScrollableIndex = (totalCount - visibleCount).coerceAtLeast(0)
                val exactScrollPos = clamped * maxScrollableIndex.toFloat()
                val targetIndex = exactScrollPos.toInt().coerceIn(0, maxItemIndex)
                val remainder = (exactScrollPos - targetIndex).coerceIn(0f, 0.999f)
                finalIndex = if (clamped >= 0.998f) maxItemIndex else targetIndex
                targetOffsetPx = if (clamped >= 0.998f) 0 else (remainder * smoothedItemSizePx).roundToInt().coerceAtLeast(0)
            }

            val contentCountForLabel = (totalCount - safeHeader).coerceAtLeast(1)
            val labelIdx = (safeHeader + (clamped * (contentCountForLabel - 1).coerceAtLeast(0)).roundToInt())
                .coerceIn(safeHeader, maxItemIndex)
            val newLabel = latestItemIndexToLabel?.invoke(labelIdx)
            activeLabel = newLabel
            if (newLabel != null && newLabel != lastHapticLabel) {
                lastHapticLabel = newLabel
                HapticUtils.performTickHaptic(context)
            }

            val indexChanged = finalIndex != lastDispatchedIndex
            val offsetChanged = abs(targetOffsetPx - lastDispatchedOffsetPx) >= 2
            if (indexChanged || offsetChanged) {
                lastDispatchedIndex = finalIndex
                lastDispatchedOffsetPx = targetOffsetPx
                if (activeScrollJob?.isActive != true) {
                    activeScrollJob = coroutineScope.launch(Dispatchers.Main.immediate) {
                        listState.scrollToItem(lastDispatchedIndex, lastDispatchedOffsetPx)
                    }
                }
            }
        }
    }

    // Show/hide visibility based on scroll activity or drag state
    val isScrollInProgress by remember(listState) {
        derivedStateOf { listState.isScrollInProgress }
    }
    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            isVisible = true
        } else {
            delay(1500L)
            if (!listState.isScrollInProgress && !isDragging) {
                isVisible = false
                activeLabel = null
            }
        }
    }

    // Update label if itemIndexToLabel is provided during drag
    LaunchedEffect(currentItemIndex, isDragging) {
        if (isDragging && latestItemIndexToLabel != null) {
            activeLabel = latestItemIndexToLabel?.invoke(currentItemIndex)
        }
    }

    val alphaAnim by animateFloatAsState(
        targetValue = if (isVisible || isDragging) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isVisible || isDragging) 80 else 260,
            easing = FastOutSlowInEasing
        ),
        label = "scrollbar_alpha"
    )

    val handleWidthDp by animateDpAsState(
        targetValue = if (isDragging) 30.dp else 26.dp,
        animationSpec = tween(durationMillis = 90),
        label = "scrollbar_thumb_width"
    )

    // Track finger grab offset relative to thumb top so the thumb never jumps on initial grab
    var grabOffsetOnThumbPx by remember { mutableFloatStateOf(thumbHeightPx / 2f) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(34.dp)
            .testTag("fast_scrollbar")
            .onSizeChanged { containerHeightPx = it.height.toFloat() }
            .graphicsLayer {
                alpha = alphaAnim
            }
            .pointerInput(topPaddingPx, bottomPaddingPx, thumbHeightPx) {
                detectTapGestures(
                    onPress = { offset ->
                        if (!isVisible && !isDragging) return@detectTapGestures
                        val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                        val currentThumbTop = topPaddingPx + (stateScrollFraction.coerceIn(0f, 1f) * usableHeight)
                        val currentThumbBottom = currentThumbTop + thumbHeightPx
                        if (offset.y in (topPaddingPx - 16f)..(containerHeightPx - bottomPaddingPx + 16f)) {
                            if (offset.y !in (currentThumbTop - 18f)..(currentThumbBottom + 18f)) {
                                HapticUtils.performTickHaptic(context)
                                val tappedFraction = ((offset.y - topPaddingPx - thumbHeightPx / 2f) / usableHeight).coerceIn(0f, 1f)
                                scrollToFractionImmediate(tappedFraction)
                            }
                        }
                    }
                )
            }
            .pointerInput(topPaddingPx, bottomPaddingPx, thumbHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                        val currentFraction = stateScrollFraction.coerceIn(0f, 1f)
                        val thumbTop = topPaddingPx + (currentFraction * usableHeight)
                        val thumbBottom = thumbTop + thumbHeightPx

                        val hitPadding = 44f
                        val isDirectThumbHit = offset.y in (thumbTop - hitPadding)..(thumbBottom + hitPadding)
                        val isTrackHitWhenVisible = isVisible && offset.y in (topPaddingPx - 20f)..(containerHeightPx - bottomPaddingPx + 20f)

                        if (isDirectThumbHit || isTrackHitWhenVisible) {
                            isDragging = true
                            isVisible = true
                            lastDispatchedIndex = -1
                            lastDispatchedOffsetPx = -1
                            HapticUtils.performClickHaptic(context)
                            if (isDirectThumbHit) {
                                grabOffsetOnThumbPx = (offset.y - thumbTop).coerceIn(0f, thumbHeightPx)
                                dragFraction = currentFraction
                                val safeHeader = latestHeaderCount.coerceIn(0, (effectiveTotalItems - 1).coerceAtLeast(0))
                                val contentCount = (effectiveTotalItems - safeHeader).coerceAtLeast(1)
                                val labelIdx = (safeHeader + (currentFraction * (contentCount - 1).coerceAtLeast(0)).roundToInt())
                                    .coerceIn(safeHeader, (effectiveTotalItems - 1).coerceAtLeast(0))
                                activeLabel = latestItemIndexToLabel?.invoke(labelIdx)
                            } else {
                                grabOffsetOnThumbPx = thumbHeightPx / 2f
                                val initialFraction = ((offset.y - topPaddingPx - grabOffsetOnThumbPx) / usableHeight).coerceIn(0f, 1f)
                                scrollToFractionImmediate(initialFraction)
                            }
                        } else {
                            isDragging = false
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        lastHapticLabel = null
                    },
                    onDragCancel = {
                        isDragging = false
                        lastHapticLabel = null
                    },
                    onVerticalDrag = { change, _ ->
                        if (!isDragging) return@detectVerticalDragGestures
                        change.consume()
                        val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                        val targetThumbTop = change.position.y - grabOffsetOnThumbPx - topPaddingPx
                        val fraction = (targetThumbTop / usableHeight).coerceIn(0f, 1f)
                        scrollToFractionImmediate(fraction)
                    }
                )
            }
    ) {
        // Track subtle guide line while actively dragging
        if (isDragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 2.dp)
                    .width(2.dp)
                    .height(with(density) { (containerHeightPx - topPaddingPx - bottomPaddingPx).coerceAtLeast(0f).toDp() })
                    .graphicsLayer {
                        translationY = topPaddingPx
                    }
                    .clip(CircleShape)
                    .background(thumbColor.copy(alpha = 0.22f))
            )
        }

        // Right-Edge Semi-Circular Handle with Up/Down Chevron Triangles (matching submitted design)
        val handleShape = RoundedCornerShape(
            topStart = 26.dp,
            bottomStart = 26.dp,
            topEnd = 0.dp,
            bottomEnd = 0.dp
        )
        val handleBgColor = androidx.compose.ui.graphics.lerp(
            com.example.ui.theme.BentoCardSurface,
            Color(0xFF241612),
            0.55f
        ).copy(alpha = if (isDragging) 0.98f else 0.92f)

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(handleWidthDp)
                .height(52.dp)
                .graphicsLayer {
                    val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                    val displayFraction = if (isDragging) dragFraction else stateScrollFraction
                    translationY = topPaddingPx + (displayFraction.coerceIn(0f, 1f) * usableHeight)
                }
                .clip(handleShape)
                .background(handleBgColor),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .size(width = 12.dp, height = 20.dp)
                    .offset(x = 2.dp)
            ) {
                val w = size.width
                val h = size.height
                val triH = h * 0.30f
                val gap = h * 0.22f
                val centerY = h / 2f

                // Upward triangle (▲)
                val upPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2f, centerY - gap / 2f - triH)
                    lineTo(0f, centerY - gap / 2f)
                    lineTo(w, centerY - gap / 2f)
                    close()
                }
                drawPath(path = upPath, color = thumbColor)

                // Downward triangle (▼)
                val downPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, centerY + gap / 2f)
                    lineTo(w, centerY + gap / 2f)
                    lineTo(w / 2f, centerY + gap / 2f + triH)
                    close()
                }
                drawPath(path = downPath, color = thumbColor)
            }
        }

        // Floating Alphabet/Section Bubble positioned cleanly to the left of the semi-circular handle
        if (isDragging && !activeLabel.isNullOrBlank()) {
            val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
            val bubbleOffsetY = (topPaddingPx + (dragFraction.coerceIn(0f, 1f) * usableHeight) + 2f).roundToInt()
            val bubbleOffsetX = -with(density) { 40.dp.roundToPx() }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(x = bubbleOffsetX, y = bubbleOffsetY)
                ) {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(70)) + scaleIn(tween(90), initialScale = 0.82f),
                        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.82f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 6.dp),
                            color = thumbColor,
                            shadowElevation = 10.dp,
                            modifier = Modifier
                                .widthIn(min = 48.dp, max = 180.dp)
                                .heightIn(min = 48.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = activeLabel.orEmpty(),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * High-responsiveness FastGridScrollbar for LazyVerticalGrid lists (such as Bento Collections grid mode).
 * Renders the right-edge semi-circular double-chevron handle without alphabet popups.
 */
@Composable
fun FastGridScrollbar(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    totalItems: Int,
    modifier: Modifier = Modifier,
    minItemsToShow: Int = 6,
    topPadding: Dp = 8.dp,
    bottomPadding: Dp = 100.dp,
    thumbColor: Color = BentoPrimary
) {
    val layoutTotalItems by remember {
        derivedStateOf { gridState.layoutInfo.totalItemsCount }
    }
    val effectiveTotalItems = if (layoutTotalItems > 0) layoutTotalItems else totalItems
    if (effectiveTotalItems < minItemsToShow) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastDispatchedIndex by remember { mutableIntStateOf(-1) }

    val topPaddingPx = remember(density, topPadding) { with(density) { topPadding.toPx() } }
    val bottomPaddingPx = remember(density, bottomPadding) { with(density) { bottomPadding.toPx() } }
    val thumbHeightPx = remember(density) { with(density) { 52.dp.toPx() } }

    val stateScrollFraction by remember(gridState, totalItems) {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val totalCount = if (layoutInfo.totalItemsCount > 0) layoutInfo.totalItemsCount else totalItems
            val visibleItems = layoutInfo.visibleItemsInfo
            val visibleCount = visibleItems.size
            if (totalCount <= 1 || visibleCount == 0) {
                0f
            } else {
                val scrollableItems = (totalCount - visibleCount).coerceAtLeast(1)
                val firstItemSize = visibleItems.firstOrNull()?.size?.height ?: 0
                val subItemFraction = if (firstItemSize > 0) {
                    (gridState.firstVisibleItemScrollOffset.toFloat() / firstItemSize.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val exactIndex = gridState.firstVisibleItemIndex.toFloat() + subItemFraction
                (exactIndex / scrollableItems.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    val latestTotalItems by rememberUpdatedState(totalItems)
    var activeScrollJob by remember { mutableStateOf<Job?>(null) }
    val scrollToFractionImmediate: (Float) -> Unit = remember(gridState, coroutineScope) {
        { targetFraction: Float ->
            val clamped = targetFraction.coerceIn(0f, 1f)
            dragFraction = clamped
            val layoutInfo = gridState.layoutInfo
            val totalCount = if (layoutInfo.totalItemsCount > 0) layoutInfo.totalItemsCount else latestTotalItems
            val maxItemIndex = (totalCount - 1).coerceAtLeast(0)
            val finalIndex = (clamped * maxItemIndex.toFloat()).roundToInt().coerceIn(0, maxItemIndex)
            if (finalIndex != lastDispatchedIndex) {
                lastDispatchedIndex = finalIndex
                if (activeScrollJob?.isActive != true) {
                    activeScrollJob = coroutineScope.launch(Dispatchers.Main.immediate) {
                        gridState.scrollToItem(lastDispatchedIndex)
                    }
                }
            }
        }
    }

    val isScrollInProgress by remember(gridState) {
        derivedStateOf { gridState.isScrollInProgress }
    }
    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            isVisible = true
        } else {
            delay(1500L)
            if (!gridState.isScrollInProgress && !isDragging) {
                isVisible = false
            }
        }
    }

    val alphaAnim by animateFloatAsState(
        targetValue = if (isVisible || isDragging) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isVisible || isDragging) 80 else 260,
            easing = FastOutSlowInEasing
        ),
        label = "grid_scrollbar_alpha"
    )

    val handleWidthDp by animateDpAsState(
        targetValue = if (isDragging) 30.dp else 26.dp,
        animationSpec = tween(durationMillis = 90),
        label = "grid_scrollbar_thumb_width"
    )

    var grabOffsetOnThumbPx by remember { mutableFloatStateOf(thumbHeightPx / 2f) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(34.dp)
            .onSizeChanged { containerHeightPx = it.height.toFloat() }
            .graphicsLayer { alpha = alphaAnim }
            .pointerInput(topPaddingPx, bottomPaddingPx, thumbHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                        val currentFraction = stateScrollFraction.coerceIn(0f, 1f)
                        val thumbTop = topPaddingPx + (currentFraction * usableHeight)
                        val thumbBottom = thumbTop + thumbHeightPx

                        val hitPadding = 44f
                        val isDirectThumbHit = offset.y in (thumbTop - hitPadding)..(thumbBottom + hitPadding)
                        val isTrackHitWhenVisible = isVisible && offset.y in (topPaddingPx - 20f)..(containerHeightPx - bottomPaddingPx + 20f)

                        if (isDirectThumbHit || isTrackHitWhenVisible) {
                            isDragging = true
                            isVisible = true
                            lastDispatchedIndex = -1
                            HapticUtils.performClickHaptic(context)
                            if (isDirectThumbHit) {
                                grabOffsetOnThumbPx = (offset.y - thumbTop).coerceIn(0f, thumbHeightPx)
                                dragFraction = currentFraction
                            } else {
                                grabOffsetOnThumbPx = thumbHeightPx / 2f
                                val initialFraction = ((offset.y - topPaddingPx - grabOffsetOnThumbPx) / usableHeight).coerceIn(0f, 1f)
                                scrollToFractionImmediate(initialFraction)
                            }
                        } else {
                            isDragging = false
                        }
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, _ ->
                        if (!isDragging) return@detectVerticalDragGestures
                        change.consume()
                        val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                        val targetThumbTop = change.position.y - grabOffsetOnThumbPx - topPaddingPx
                        val fraction = (targetThumbTop / usableHeight).coerceIn(0f, 1f)
                        scrollToFractionImmediate(fraction)
                    }
                )
            }
    ) {
        val handleShape = RoundedCornerShape(
            topStart = 26.dp,
            bottomStart = 26.dp,
            topEnd = 0.dp,
            bottomEnd = 0.dp
        )
        val handleBgColor = androidx.compose.ui.graphics.lerp(
            com.example.ui.theme.BentoCardSurface,
            Color(0xFF241612),
            0.55f
        ).copy(alpha = if (isDragging) 0.98f else 0.92f)

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(handleWidthDp)
                .height(52.dp)
                .graphicsLayer {
                    val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                    val displayFraction = if (isDragging) dragFraction else stateScrollFraction
                    translationY = topPaddingPx + (displayFraction.coerceIn(0f, 1f) * usableHeight)
                }
                .clip(handleShape)
                .background(handleBgColor),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .size(width = 12.dp, height = 20.dp)
                    .offset(x = 2.dp)
            ) {
                val w = size.width
                val h = size.height
                val triH = h * 0.30f
                val gap = h * 0.22f
                val centerY = h / 2f

                val upPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2f, centerY - gap / 2f - triH)
                    lineTo(0f, centerY - gap / 2f)
                    lineTo(w, centerY - gap / 2f)
                    close()
                }
                drawPath(path = upPath, color = thumbColor)

                val downPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, centerY + gap / 2f)
                    lineTo(w, centerY + gap / 2f)
                    lineTo(w / 2f, centerY + gap / 2f + triH)
                    close()
                }
                drawPath(path = downPath, color = thumbColor)
            }
        }
    }
}
