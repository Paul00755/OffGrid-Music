package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    progressMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onClick: () -> Unit,
    showPrevious: Boolean = true,
    showNext: Boolean = true,
    showFavorite: Boolean = true,
    onSkipPrevious: (() -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null,
    style: String = "FLOATING",
    swipeToSkip: Boolean = true,
    swipeUpToOpen: Boolean = true,
    swipeDownToDismiss: Boolean = false,
    onDismissQueueAndStop: (() -> Unit)? = null,
    showProgress: Boolean = true,
    circularArt: Boolean = false,
    progressFractionProvider: (() -> Float)? = null,
    playbackPositionFlow: StateFlow<Long>? = null,
    modifier: Modifier = Modifier
) {
    if (song == null) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val normalizedStyle = style.uppercase()
    val isBottomBar = normalizedStyle == "BOTTOM_BAR"
    val isArtworkGlass = normalizedStyle == "ARTWORK_GLASS"
    val isCompactMinimal = normalizedStyle == "COMPACT_MINIMAL"

    val cardShape = when {
        isBottomBar -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        isCompactMinimal -> RoundedCornerShape(999.dp)
        isArtworkGlass -> RoundedCornerShape(22.dp)
        else -> RoundedCornerShape(24.dp)
    }
    val cardPadding = when {
        isBottomBar -> Modifier.padding(horizontal = 0.dp, vertical = 0.dp)
        isCompactMinimal -> Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
        else -> Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
    }

    val dragOffsetX = remember { Animatable(0f) }
    val dragOffsetY = remember { Animatable(0f) }
    val horizontalThresholdPx = with(density) { 52.dp.toPx() }
    val verticalThresholdPx = with(density) { 38.dp.toPx() }
    val maxDragXPx = with(density) { 44.dp.toPx() }
    val maxDragYPx = with(density) { 24.dp.toPx() }

    val horizontalSwipeModifier = if (swipeToSkip) {
        Modifier.pointerInput(song.id, onSkipNext, onSkipPrevious) {
            var accumulatedX = 0f
            detectHorizontalDragGestures(
                onDragStart = { accumulatedX = 0f },
                onHorizontalDrag = { _, dragAmount ->
                    accumulatedX += dragAmount
                    scope.launch {
                        dragOffsetX.snapTo((accumulatedX * 0.45f).coerceIn(-maxDragXPx, maxDragXPx))
                    }
                },
                onDragEnd = {
                    when {
                        accumulatedX <= -horizontalThresholdPx -> {
                            HapticUtils.performClickHaptic(context)
                            onSkipNext()
                        }
                        accumulatedX >= horizontalThresholdPx && onSkipPrevious != null -> {
                            HapticUtils.performClickHaptic(context)
                            onSkipPrevious()
                        }
                    }
                    accumulatedX = 0f
                    scope.launch {
                        dragOffsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                },
                onDragCancel = {
                    accumulatedX = 0f
                    scope.launch {
                        dragOffsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                }
            )
        }
    } else Modifier

    val maxDragDownPx = with(density) { 58.dp.toPx() }
    val dismissThresholdPx = with(density) { 46.dp.toPx() }
    val canSwipeDown = swipeDownToDismiss && onDismissQueueAndStop != null

    val verticalSwipeModifier = if (swipeUpToOpen || canSwipeDown) {
        Modifier.pointerInput(song.id, swipeUpToOpen, canSwipeDown, onClick, onDismissQueueAndStop) {
            var accumulatedY = 0f
            detectVerticalDragGestures(
                onDragStart = { accumulatedY = 0f },
                onVerticalDrag = { _, dragAmount ->
                    accumulatedY += dragAmount
                    val clampedAccum = when {
                        swipeUpToOpen && canSwipeDown -> accumulatedY
                        swipeUpToOpen -> accumulatedY.coerceAtMost(0f)
                        canSwipeDown -> accumulatedY.coerceAtLeast(0f)
                        else -> 0f
                    }
                    accumulatedY = clampedAccum
                    scope.launch {
                        val visualOffset = if (clampedAccum < 0f) {
                            (clampedAccum * 0.35f).coerceIn(-maxDragYPx, 0f)
                        } else {
                            (clampedAccum * 0.55f).coerceIn(0f, maxDragDownPx)
                        }
                        dragOffsetY.snapTo(visualOffset)
                    }
                },
                onDragEnd = {
                    when {
                        swipeUpToOpen && accumulatedY <= -verticalThresholdPx -> {
                            HapticUtils.performClickHaptic(context)
                            onClick()
                        }
                        canSwipeDown && accumulatedY >= dismissThresholdPx -> {
                            HapticUtils.performClickHaptic(context)
                            onDismissQueueAndStop?.invoke()
                        }
                    }
                    accumulatedY = 0f
                    scope.launch {
                        dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                },
                onDragCancel = {
                    accumulatedY = 0f
                    scope.launch {
                        dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                }
            )
        }
    } else Modifier

    val artSize = when {
        isCompactMinimal -> 34.dp
        isArtworkGlass -> 44.dp
        else -> 40.dp
    }
    val artShape = if (circularArt || isCompactMinimal) CircleShape else RoundedCornerShape(10.dp)
    val artCornerRadius = if (circularArt || isCompactMinimal) 999 else 10

    val containerBgModifier = if (isArtworkGlass) {
        Modifier.background(
            Brush.horizontalGradient(
                colors = listOf(
                    BentoPrimary.copy(alpha = 0.24f),
                    BentoCardSurface.copy(alpha = 0.90f),
                    BentoPrimary.copy(alpha = 0.14f)
                )
            )
        )
    } else {
        Modifier.background(BentoCardSurface)
    }

    val containerBorderColor = when {
        isArtworkGlass -> BentoPrimary.copy(alpha = 0.42f)
        isCompactMinimal -> BentoBorder.copy(alpha = 0.72f)
        else -> BentoBorder
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(cardPadding)
            .graphicsLayer {
                translationX = dragOffsetX.value
                translationY = dragOffsetY.value
                if (canSwipeDown && dragOffsetY.value > 0f && maxDragDownPx > 0f) {
                    alpha = (1f - (dragOffsetY.value / maxDragDownPx) * 0.55f).coerceIn(0.45f, 1f)
                }
            }
            .clip(cardShape)
            .then(containerBgModifier)
            .border(
                border = BorderStroke(1.dp, containerBorderColor),
                shape = cardShape
            )
            .then(horizontalSwipeModifier)
            .then(verticalSwipeModifier)
            .clickable { onClick() }
            .testTag("mini_player_container")
    ) {
        Column {
            if (isArtworkGlass) {
                // Subtle top handle indicator hinting swipe-up-to-open
                if (swipeUpToOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(BentoTextSubtle.copy(alpha = 0.32f))
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (isCompactMinimal) 8.dp else 10.dp,
                        vertical = if (isCompactMinimal) 4.dp else 6.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Album Art Thumbnail
                Box(
                    modifier = Modifier
                        .size(artSize)
                        .clip(artShape)
                        .background(BentoCardPurple)
                        .then(
                            if (circularArt || isArtworkGlass) {
                                Modifier.border(1.dp, BentoPrimary.copy(alpha = 0.35f), artShape)
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AlbumArtView(
                        song = song,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = artCornerRadius
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Song Info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = song.displayTitle,
                        fontSize = if (isCompactMinimal) 13.sp else 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.displayArtist,
                        fontSize = if (isCompactMinimal) 11.sp else 11.5.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                val compactButtonSize = if (isCompactMinimal) 28.dp else 34.dp
                val compactIconSize = if (isCompactMinimal) 17.dp else 20.dp
                val compactSpacer = if (isCompactMinimal) 2.dp else 3.dp

                if (showFavorite && onFavoriteToggle != null) {
                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onFavoriteToggle()
                        },
                        modifier = Modifier
                            .size(compactButtonSize)
                            .testTag("mini_player_favorite")
                    ) {
                        AnimatedFavoriteIcon(
                            isFavorite = song.isFavorite,
                            activeTint = BentoAccentPinkText,
                            inactiveTint = BentoTextSecondary,
                            size = compactIconSize,
                            contentDescription = "Favorite",
                            animationKey = song.id
                        )
                    }
                    Spacer(modifier = Modifier.width(compactSpacer))
                }

                if (showPrevious && onSkipPrevious != null) {
                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onSkipPrevious()
                        },
                        modifier = Modifier
                            .size(compactButtonSize)
                            .testTag("mini_player_previous")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(compactIconSize)
                        )
                    }
                    Spacer(modifier = Modifier.width(compactSpacer))
                }

                // Play/Pause Button
                val playButtonSize = when {
                    isCompactMinimal -> 34.dp
                    isArtworkGlass -> 40.dp
                    else -> 38.dp
                }
                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onTogglePlayPause()
                    },
                    modifier = Modifier
                        .size(playButtonSize)
                        .clip(CircleShape)
                        .background(BentoPrimary)
                        .testTag("mini_player_play_pause")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = BentoOnPrimary,
                        modifier = Modifier.size(if (isCompactMinimal) 19.dp else 22.dp)
                    )
                }

                if (showNext) {
                    Spacer(modifier = Modifier.width(3.dp))

                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onSkipNext()
                        },
                        modifier = Modifier
                            .size(if (isCompactMinimal) 32.dp else 36.dp)
                            .testTag("mini_player_next")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(if (isCompactMinimal) 20.dp else 22.dp)
                        )
                    }
                }
            }

            if (showProgress) {
                MiniPlayerProgress(
                    songDurationMs = song.durationMs,
                    durationMs = durationMs,
                    progressMs = progressMs,
                    progressFractionProvider = progressFractionProvider,
                    playbackPositionFlow = playbackPositionFlow,
                    isCompactMinimal = isCompactMinimal
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerProgress(
    songDurationMs: Long,
    durationMs: Long,
    progressMs: Long,
    progressFractionProvider: (() -> Float)?,
    playbackPositionFlow: StateFlow<Long>?,
    isCompactMinimal: Boolean = false
) {
    val livePositionState = playbackPositionFlow?.collectAsStateWithLifecycle()
    val effectiveDuration = if (durationMs > 0L) durationMs else songDurationMs
    LinearProgressIndicator(
        progress = {
            when {
                livePositionState != null && effectiveDuration > 0L ->
                    (livePositionState.value.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
                progressFractionProvider != null -> progressFractionProvider().coerceIn(0f, 1f)
                effectiveDuration > 0L -> (progressMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
                else -> 0f
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isCompactMinimal) 20.dp else 0.dp)
            .height(if (isCompactMinimal) 2.5.dp else 3.dp),
        color = BentoPrimary,
        trackColor = BentoCardBorder.copy(alpha = 0.55f)
    )
}
