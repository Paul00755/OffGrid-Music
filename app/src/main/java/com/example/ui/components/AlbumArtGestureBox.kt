package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BentoPrimary
import com.example.util.HapticUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reusable gesture container for album artwork across all Now Playing themes.
 * Supports:
 * - Tap: Play / Pause toggle with visual feedback
 * - Horizontal swipe left (drag < -threshold): Previous track
 * - Horizontal swipe right (drag > threshold): Next track
 * - Physical spring-back animation while dragging
 * - Gracefully lets vertical drag pass through for collapse/lyrics
 */
@Composable
fun AlbumArtGestureBox(
    enabled: Boolean,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            content()
        }
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dragOffsetX = remember { Animatable(0f) }
    var feedbackIcon by remember { mutableStateOf<ImageVector?>(null) }
    var feedbackJob by remember { mutableStateOf<Job?>(null) }

    val currentEnabled by rememberUpdatedState(enabled)
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentOnTogglePlayPause by rememberUpdatedState(onTogglePlayPause)
    val currentOnSkipPrevious by rememberUpdatedState(onSkipPrevious)
    val currentOnSkipNext by rememberUpdatedState(onSkipNext)

    fun triggerFeedback(icon: ImageVector) {
        feedbackJob?.cancel()
        feedbackIcon = icon
        feedbackJob = coroutineScope.launch {
            delay(500)
            feedbackIcon = null
        }
    }

    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 40.dp.toPx() }
    val maxDragOffsetPx = with(density) { 50.dp.toPx() }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    if (!currentEnabled) return@awaitEachGesture
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDragX = 0f
                    var totalDragY = 0f
                    var isHorizontalDrag = false
                    var isVerticalDrag = false
                    var hasTriggeredInGesture = false
                    val touchSlop = viewConfiguration.touchSlop

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            // Touch released / lifted
                            if (!isHorizontalDrag && !isVerticalDrag && !hasTriggeredInGesture) {
                                // Tap: Play/Pause
                                change.consume()
                                HapticUtils.performClickHaptic(context)
                                triggerFeedback(if (currentIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow)
                                currentOnTogglePlayPause()
                            } else if (isHorizontalDrag && !hasTriggeredInGesture) {
                                if (totalDragX < -swipeThresholdPx) {
                                    // Swipe Left: Next track
                                    hasTriggeredInGesture = true
                                    change.consume()
                                    HapticUtils.performClickHaptic(context)
                                    triggerFeedback(Icons.Default.SkipNext)
                                    currentOnSkipNext()
                                } else if (totalDragX > swipeThresholdPx) {
                                    // Swipe Right: Previous track
                                    hasTriggeredInGesture = true
                                    change.consume()
                                    HapticUtils.performClickHaptic(context)
                                    triggerFeedback(Icons.Default.SkipPrevious)
                                    currentOnSkipPrevious()
                                }
                            }

                            coroutineScope.launch {
                                dragOffsetX.animateTo(
                                    0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }
                            break
                        } else {
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            totalDragX += dx
                            totalDragY += dy

                            if (!isHorizontalDrag && !isVerticalDrag) {
                                if (abs(totalDragX) > touchSlop && abs(totalDragX) > abs(totalDragY) * 1.15f) {
                                    isHorizontalDrag = true
                                    change.consume()
                                } else if (abs(totalDragY) > touchSlop) {
                                    isVerticalDrag = true
                                    // Vertical drag: do not consume, propagate to parent
                                }
                            } else if (isHorizontalDrag) {
                                change.consume()
                                val dampedDrag = (totalDragX * 0.45f).coerceIn(-maxDragOffsetPx, maxDragOffsetPx)
                                coroutineScope.launch {
                                    dragOffsetX.snapTo(dampedDrag)
                                }

                                // Instant trigger on crossing swipe threshold
                                if (!hasTriggeredInGesture) {
                                    if (totalDragX < -swipeThresholdPx) {
                                        // Swipe Left: Next track
                                        hasTriggeredInGesture = true
                                        HapticUtils.performClickHaptic(context)
                                        triggerFeedback(Icons.Default.SkipNext)
                                        currentOnSkipNext()
                                    } else if (totalDragX > swipeThresholdPx) {
                                        // Swipe Right: Previous track
                                        hasTriggeredInGesture = true
                                        HapticUtils.performClickHaptic(context)
                                        triggerFeedback(Icons.Default.SkipPrevious)
                                        currentOnSkipPrevious()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .offset { IntOffset(dragOffsetX.value.roundToInt(), 0) },
        contentAlignment = Alignment.Center
    ) {
        content()

        // Subtle tactile feedback indicator on action
        AnimatedVisibility(
            visible = feedbackIcon != null,
            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessHigh)),
            exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessHigh)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            feedbackIcon?.let { icon ->
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
