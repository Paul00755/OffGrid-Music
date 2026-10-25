package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * High-performance VSYNC-locked fast-scroll thumb indicator for LazyLists.
 * - Synchronizes `effectiveTotalItems` with `listState.layoutInfo.totalItemsCount` so sticky headers
 *   and top control rows never cause index out-of-bounds or thumb drift.
 * - Uses `withFrameNanos` during active thumb dragging so scrolling is locked to native 60Hz/90Hz/120Hz VSYNC.
 */
@Composable
fun FastScrollbar(
    listState: LazyListState,
    totalItems: Int,
    modifier: Modifier = Modifier,
    topPadding: androidx.compose.ui.unit.Dp = 8.dp,
    bottomPadding: androidx.compose.ui.unit.Dp = 16.dp,
    itemLabelProvider: ((Int) -> String?)? = null
) {
    val effectiveTotalItems by remember(totalItems) {
        derivedStateOf {
            val layoutTotal = listState.layoutInfo.totalItemsCount
            if (layoutTotal > 0) layoutTotal else totalItems
        }
    }

    if (effectiveTotalItems <= 3 && totalItems <= 3) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isVisible by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastScrolledIndex by remember { mutableIntStateOf(-1) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var lastLabel by remember { mutableStateOf<String?>(null) }

    val thumbHeightDp = 36.dp
    val thumbWidthDp = 12.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
    val topPaddingPx = with(density) { topPadding.toPx() }
    val bottomPaddingPx = with(density) { bottomPadding.toPx() }

    val stateScrollFraction by remember {
        derivedStateOf {
            val count = effectiveTotalItems
            if (count <= 1) 0f
            else {
                val firstVisible = listState.firstVisibleItemIndex.toFloat()
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val offsetFraction = if (visibleItems.isNotEmpty()) {
                    val itemSize = visibleItems.first().size.toFloat().coerceAtLeast(1f)
                    (listState.firstVisibleItemScrollOffset.toFloat() / itemSize).coerceIn(0f, 1f)
                } else 0f
                val visibleCount = visibleItems.size.coerceAtLeast(1)
                val totalScrollable = (count - visibleCount).coerceAtLeast(1).toFloat()
                ((firstVisible + offsetFraction) / totalScrollable).coerceIn(0f, 1f)
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress, isDragging) {
        if (listState.isScrollInProgress || isDragging) {
            isVisible = true
        } else {
            delay(1400L)
            if (!listState.isScrollInProgress && !isDragging) {
                isVisible = false
            }
        }
    }

    val alphaAnim by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "fast_scroll_alpha"
    )

    val scaleAnim by animateFloatAsState(
        targetValue = if (isDragging) 1.15f else if (isVisible) 1f else 0.85f,
        animationSpec = spring(dampingRatio = 0.85f),
        label = "fast_scroll_scale"
    )

    if (alphaAnim <= 0.01f && !isVisible) return
    if (effectiveTotalItems <= 0) return

    val maxIndex = (effectiveTotalItems - 1).coerceAtLeast(0)
    val activeLabel = if (isDragging) {
        val currentItemIndex = (dragFraction * maxIndex).roundToInt().coerceIn(0, maxIndex)
        itemLabelProvider?.invoke(currentItemIndex)?.takeIf { it.isNotBlank() }
    } else null

    var pendingTargetIndex by remember { mutableIntStateOf(-1) }

    // VSYNC-locked scroll dispatcher for 60Hz / 90Hz / 120Hz displays
    LaunchedEffect(isDragging) {
        if (isDragging) {
            while (isDragging) {
                val target = pendingTargetIndex
                val safeMax = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                val boundedTarget = target.coerceIn(0, safeMax)
                if (target >= 0 && boundedTarget != listState.firstVisibleItemIndex) {
                    try {
                        listState.scrollToItem(boundedTarget)
                    } catch (_: Throwable) {}
                }
                try {
                    withFrameNanos { /* wait for next VSYNC frame */ }
                } catch (_: Exception) {
                    delay(8L)
                }
            }
        }
    }

    fun scrollToFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        dragFraction = clamped
        val currentMax = (effectiveTotalItems - 1).coerceAtLeast(0)
        val targetIndex = (clamped * currentMax).roundToInt().coerceIn(0, currentMax)
        if (targetIndex != lastScrolledIndex) {
            lastScrolledIndex = targetIndex
            val newLabel = try { itemLabelProvider?.invoke(targetIndex) } catch (_: Exception) { null }
            if (newLabel != null && newLabel != lastLabel) {
                lastLabel = newLabel
                HapticUtils.performTickHaptic(context)
            }
            pendingTargetIndex = targetIndex
        }
    }

    val touchWidthDp = 24.dp
    val usableHeightPx = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(0f)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(touchWidthDp)
            .onSizeChanged { containerHeightPx = it.height }
            .graphicsLayer {
                alpha = alphaAnim
                scaleX = scaleAnim
                scaleY = scaleAnim
            }
            .pointerInput(Unit) {
                val touchSlopPx = 32.dp.toPx()
                detectDragGestures(
                    onDragStart = { offset ->
                        val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                        val currentThumbY = topPaddingPx + (usableHeight * (if (isDragging) dragFraction else stateScrollFraction))
                        val thumbTop = currentThumbY - touchSlopPx
                        val thumbBottom = currentThumbY + thumbHeightPx + touchSlopPx
                        if (offset.y in thumbTop..thumbBottom) {
                            isDragging = true
                            isVisible = true
                            dragFraction = stateScrollFraction.coerceIn(0f, 1f)
                        } else {
                            isDragging = false
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        val target = pendingTargetIndex
                        val safeMax = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                        if (target >= 0) {
                            coroutineScope.launch {
                                try { listState.scrollToItem(target.coerceIn(0, safeMax)) } catch (_: Throwable) {}
                            }
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onDrag = { change, dragAmount ->
                        if (isDragging) {
                            change.consume()
                            val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                            val deltaFraction = dragAmount.y / usableHeight
                            val fraction = (dragFraction + deltaFraction).coerceIn(0f, 1f)
                            scrollToFraction(fraction)
                        }
                    }
                )
            },
        contentAlignment = Alignment.TopEnd
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    translationY = topPaddingPx + (usableHeightPx * if (isDragging) dragFraction else stateScrollFraction)
                }
                .padding(end = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isDragging && activeLabel != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BentoCardDarkPurple,
                    shadowElevation = 6.dp,
                    modifier = Modifier.border(1.5.dp, BentoPrimary.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                ) {
                    Box(
                        modifier = Modifier.size(width = 36.dp, height = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = activeLabel,
                            color = BentoTextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(width = thumbWidthDp, height = thumbHeightDp)
                    .shadow(elevation = if (isDragging) 6.dp else 2.dp, shape = RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isDragging) BentoPrimary else BentoCardDarkPurple.copy(alpha = 0.95f))
                    .border(
                        width = 1.dp,
                        color = if (isDragging) Color.White.copy(alpha = 0.8f) else BentoPrimary.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.UnfoldMore,
                    contentDescription = "Scroll list quickly",
                    tint = if (isDragging) BentoOnPrimary else BentoPrimary,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}