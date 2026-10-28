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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BentoPrimary
import com.example.util.HapticUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * High-responsiveness, zero-lag FastScrollbar for LazyColumn lists.
 *
 * Features:
 * - Instant 1:1 finger tracking with zero coordinate distortion (only the thumb scales, never the touch container).
 * - Sub-item pixel scroll interpolation so both short lists and 10,000-item libraries glide at 120fps.
 * - Direct track tap/drag support when visible so grabbing slightly outside the thumb still responds immediately.
 * - Conflated Main.immediate scroll dispatch for zero input latency.
 */
@Composable
fun FastScrollbar(
    listState: LazyListState,
    totalItems: Int,
    modifier: Modifier = Modifier,
    itemIndexToLabel: ((Int) -> String?)? = null,
    itemLabelProvider: ((Int) -> String?)? = null,
    topPadding: Dp = 8.dp,
    bottomPadding: Dp = 100.dp,
    thumbColor: Color = BentoPrimary
) {
    val resolvedLabelProvider = itemIndexToLabel ?: itemLabelProvider
    val layoutTotalItems by remember {
        derivedStateOf { listState.layoutInfo.totalItemsCount }
    }
    val effectiveTotalItems = if (layoutTotalItems > 0) layoutTotalItems else totalItems
    if (effectiveTotalItems <= 8) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var lastHapticLabel by remember { mutableStateOf<String?>(null) }

    val topPaddingPx = remember(density, topPadding) { with(density) { topPadding.toPx() } }
    val bottomPaddingPx = remember(density, bottomPadding) { with(density) { bottomPadding.toPx() } }
    val thumbHeightPx = remember(density) { with(density) { 46.dp.toPx() } }

    // Continuous sub-item scroll fraction so the thumb glides smoothly pixel-by-pixel
    val stateScrollFraction by remember(listState, totalItems) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalCount = if (layoutInfo.totalItemsCount > 0) layoutInfo.totalItemsCount else totalItems
            val visibleItems = layoutInfo.visibleItemsInfo
            val visibleCount = visibleItems.size
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

    val currentItemIndex by remember(listState, totalItems, isDragging, dragFraction) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalCount = if (layoutInfo.totalItemsCount > 0) layoutInfo.totalItemsCount else totalItems
            if (isDragging) {
                (dragFraction * (totalCount - 1).coerceAtLeast(0)).roundToInt().coerceIn(0, (totalCount - 1).coerceAtLeast(0))
            } else {
                listState.firstVisibleItemIndex.coerceIn(0, (totalCount - 1).coerceAtLeast(0))
            }
        }
    }

    val latestItemIndexToLabel by rememberUpdatedState(resolvedLabelProvider)
    val latestTotalItems by rememberUpdatedState(totalItems)

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
            val maxScrollableIndex = (totalCount - visibleCount).coerceAtLeast(0)
            val maxItemIndex = (totalCount - 1).coerceAtLeast(0)

            val exactScrollPos = clamped * maxScrollableIndex.toFloat()
            val targetIndex = exactScrollPos.toInt().coerceIn(0, maxItemIndex)
            val remainder = (exactScrollPos - targetIndex).coerceIn(0f, 0.999f)

            val avgItemSizePx = if (visibleItems.isNotEmpty()) {
                var sum = 0
                for (i in visibleItems.indices) sum += visibleItems[i].size
                (sum.toFloat() / visibleItems.size.toFloat()).coerceAtLeast(1f)
            } else {
                160f
            }
            val targetOffsetPx = if (clamped >= 0.998f) {
                0
            } else {
                (remainder * avgItemSizePx).roundToInt().coerceAtLeast(0)
            }

            val labelIdx = (clamped * maxItemIndex.toFloat()).roundToInt().coerceIn(0, maxItemIndex)
            val newLabel = latestItemIndexToLabel?.invoke(labelIdx)
            activeLabel = newLabel
            if (newLabel != null && newLabel != lastHapticLabel) {
                lastHapticLabel = newLabel
                HapticUtils.performTickHaptic(context)
            }

            activeScrollJob?.cancel()
            activeScrollJob = coroutineScope.launch(Dispatchers.Main.immediate) {
                val finalIndex = if (clamped >= 0.998f) maxItemIndex else targetIndex
                listState.scrollToItem(finalIndex, targetOffsetPx)
            }
        }
    }

    // Show/hide visibility based on scroll activity or drag state
    val isScrollInProgress = listState.isScrollInProgress
    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            isVisible = true
        } else {
            delay(1400L)
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
            durationMillis = if (isVisible || isDragging) 90 else 280,
            easing = FastOutSlowInEasing
        ),
        label = "scrollbar_alpha"
    )

    val thumbWidthDp by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 5.dp,
        animationSpec = tween(durationMillis = 100),
        label = "scrollbar_thumb_width"
    )

    // Track finger grab offset relative to thumb top so the thumb never jumps on initial grab
    var grabOffsetOnThumbPx by remember { mutableFloatStateOf(thumbHeightPx / 2f) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(20.dp)
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
                        // Allow tapping directly on the thumb or along the active scrollbar track
                        if (offset.y in (topPaddingPx - 12f)..(containerHeightPx - bottomPaddingPx + 12f)) {
                            if (offset.y !in (currentThumbTop - 16f)..(currentThumbBottom + 16f)) {
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

                        val hitPadding = 32f
                        val isDirectThumbHit = offset.y in (thumbTop - hitPadding)..(thumbBottom + hitPadding)
                        val isTrackHitWhenVisible = isVisible && offset.y in (topPaddingPx - 16f)..(containerHeightPx - bottomPaddingPx + 16f)

                        if (isDirectThumbHit || isTrackHitWhenVisible) {
                            isDragging = true
                            isVisible = true
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
                        // Direct 1:1 finger-to-thumb coordinate lock (zero cumulative delta drift)
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
                    .padding(end = 7.dp)
                    .width(2.dp)
                    .height(with(density) { (containerHeightPx - topPaddingPx - bottomPaddingPx).coerceAtLeast(0f).toDp() })
                    .offset { IntOffset(x = 0, y = topPaddingPx.roundToInt()) }
                    .clip(CircleShape)
                    .background(thumbColor.copy(alpha = 0.18f))
            )
        }

        // Thumb Pill — uses IntOffset for immediate layout-consistent positioning with 0 lag
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .width(thumbWidthDp)
                .height(46.dp)
                .offset {
                    val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                    val displayFraction = if (isDragging) dragFraction else stateScrollFraction
                    val yPx = topPaddingPx + (displayFraction.coerceIn(0f, 1f) * usableHeight)
                    IntOffset(x = 0, y = yPx.roundToInt())
                }
                .clip(CircleShape)
                .background(if (isDragging) thumbColor else thumbColor.copy(alpha = 0.82f))
        )

        // Floating section / letter bubble while dragging
        AnimatedVisibility(
            visible = isDragging && !activeLabel.isNullOrBlank(),
            enter = fadeIn(tween(80)) + scaleIn(tween(100), initialScale = 0.8f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                    val displayFraction = if (isDragging) dragFraction else stateScrollFraction
                    val yOffset = (topPaddingPx + (displayFraction.coerceIn(0f, 1f) * usableHeight) - 6f).roundToInt()
                    IntOffset(x = with(density) { (-44).dp.roundToPx() }, y = yOffset)
                }
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = thumbColor,
                shadowElevation = 8.dp,
                modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 40.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = activeLabel ?: "",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }
            }
        }
    }
}