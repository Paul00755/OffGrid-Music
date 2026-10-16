package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
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
 * A sleek, high-performance, non-intrusive fast-scroll thumb indicator for LazyLists.
 * - Positioned flush against the far right edge so it never intercepts 3-dot song action menus.
 * - Smoothly fades in while scrolling or dragging, and automatically fades out after inactivity.
 * - Low-overhead gesture handling for buttery-smooth 60/120fps scrolling.
 * - Supports alphabetical index bubble without lag.
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
    if (totalItems <= 3) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isVisible by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastScrolledIndex by remember { mutableIntStateOf(-1) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var lastLabel by remember { mutableStateOf<String?>(null) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val thumbHeightDp = 34.dp
    val thumbWidthDp = 12.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
    val topPaddingPx = with(density) { topPadding.toPx() }
    val bottomPaddingPx = with(density) { bottomPadding.toPx() }

    // Derived smooth scroll fraction from list state with item offset precision
    val stateScrollFraction by remember(totalItems) {
        derivedStateOf {
            if (totalItems <= 1) 0f
            else {
                val firstVisible = listState.firstVisibleItemIndex.toFloat()
                val offsetFraction = if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                    val itemSize = listState.layoutInfo.visibleItemsInfo.first().size.toFloat().coerceAtLeast(1f)
                    listState.firstVisibleItemScrollOffset.toFloat() / itemSize
                } else 0f
                val visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                val totalScrollable = (totalItems - visibleCount).coerceAtLeast(1).toFloat()
                ((firstVisible + offsetFraction) / totalScrollable).coerceIn(0f, 1f)
            }
        }
    }

    // Auto-hide timer logic: stay visible when scrolling or dragging, fade out after 1400ms
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
        animationSpec = tween(durationMillis = 180),
        label = "fast_scroll_alpha"
    )

    val scaleAnim by animateFloatAsState(
        targetValue = if (isDragging) 1.15f else if (isVisible) 1f else 0.8f,
        animationSpec = spring(dampingRatio = 0.85f),
        label = "fast_scroll_scale"
    )

    if (alphaAnim <= 0.01f && !isVisible) return
    if (totalItems <= 0) return

    val maxIndex = (totalItems - 1).coerceAtLeast(0)
    val activeLabel = if (isDragging) {
        val currentItemIndex = (dragFraction * maxIndex).roundToInt().coerceIn(0, maxIndex)
        itemLabelProvider?.invoke(currentItemIndex)?.takeIf { it.isNotBlank() }
    } else null

    var pendingTargetIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(isDragging) {
        if (isDragging) {
            while (isDragging) {
                val target = pendingTargetIndex
                if (target >= 0 && target != listState.firstVisibleItemIndex) {
                    try {
                        listState.scrollToItem(target)
                    } catch (_: Exception) {}
                }
                kotlinx.coroutines.delay(16)
            }
        }
    }

    fun scrollToFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        dragFraction = clamped
        val targetIndex = (clamped * maxIndex).roundToInt().coerceIn(0, maxIndex)
        if (targetIndex != lastScrolledIndex) {
            lastScrolledIndex = targetIndex
            val newLabel = itemLabelProvider?.invoke(targetIndex)
            if (newLabel != null && newLabel != lastLabel) {
                lastLabel = newLabel
                HapticUtils.performTickHaptic(context)
            }
            pendingTargetIndex = targetIndex
        }
    }

    // Expanded touch target (22.dp) for effortless grabbing without needing pixel-perfect edge taps,
    // while keeping thumb slim (12.dp) and safe from adjacent action items
    val touchWidthDp = 22.dp

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
            .pointerInput(totalItems) {
                val touchSlopPx = 28.dp.toPx()
                detectDragGestures(
                    onDragStart = { offset ->
                        val usableHeight = (containerHeightPx - topPaddingPx - bottomPaddingPx - thumbHeightPx).coerceAtLeast(1f)
                        val currentThumbY = topPaddingPx + (usableHeight * (if (isDragging) dragFraction else stateScrollFraction))
                        val thumbTop = currentThumbY - touchSlopPx
                        val thumbBottom = currentThumbY + thumbHeightPx + touchSlopPx
                        // Only engage fast-scrolling if the drag starts directly on or near the visible scrollbar thumb!
                        // Tapping or dragging on the empty area below/above will NEVER teleport or drag the list down.
                        if (offset.y in thumbTop..thumbBottom) {
                            isDragging = true
                            isVisible = true
                            dragFraction = (if (isDragging) dragFraction else stateScrollFraction).coerceIn(0f, 1f)
                        } else {
                            isDragging = false
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        val target = pendingTargetIndex
                        if (target >= 0) {
                            coroutineScope.launch {
                                try { listState.scrollToItem(target) } catch (_: Exception) {}
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
            // Alphabetical section badge / bubble indicator (e.g. "A", "B", "C", ...)
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

            // Compact Pill Scrollable Thumb Icon
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
