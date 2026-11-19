package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class FavAnimMode {
    IDLE,
    FAVORITING,
    UNFAVORITING
}

/**
 * Minimal, silky-smooth Favorite icon with two distinct micro-animations:
 * - Favoriting (`false -> true`): spring pop scale + subtle pastel ring & 6-dot spark burst.
 * - Unfavoriting (`true -> false`): breaking-heart animation where the filled heart cracks
 *   along a jagged center seam into left & right halves that tilt outward, drop, and fade
 *   out to reveal the outlined heart.
 *
 * Pass a stable [animationKey] (such as `song.id`) so list recycling or track changes never
 * trigger spurious animations when the displayed item changes.
 */
@Composable
fun AnimatedFavoriteIcon(
    isFavorite: Boolean,
    activeTint: Color,
    inactiveTint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    contentDescription: String? = "Favorite",
    animationKey: Any? = Unit
) {
    var trackedKey by remember { mutableStateOf(animationKey) }
    var trackedFavorite by remember { mutableStateOf(isFavorite) }
    var animMode by remember { mutableStateOf(FavAnimMode.IDLE) }

    val scaleAnim = remember { Animatable(1f) }
    val burstProgress = remember { Animatable(0f) }
    val breakProgress = remember { Animatable(0f) }

    LaunchedEffect(animationKey, isFavorite) {
        if (trackedKey != animationKey) {
            trackedKey = animationKey
            trackedFavorite = isFavorite
            animMode = FavAnimMode.IDLE
            scaleAnim.snapTo(1f)
            burstProgress.snapTo(0f)
            breakProgress.snapTo(0f)
            return@LaunchedEffect
        }

        if (trackedFavorite != isFavorite) {
            trackedFavorite = isFavorite
            if (isFavorite) {
                animMode = FavAnimMode.FAVORITING
                breakProgress.snapTo(0f)
                scaleAnim.snapTo(0.68f)
                burstProgress.snapTo(0f)

                val scaleJob = launch {
                    scaleAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                }
                val burstJob = launch {
                    burstProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                    )
                }
                scaleJob.join()
                burstJob.join()
                animMode = FavAnimMode.IDLE
            } else {
                animMode = FavAnimMode.UNFAVORITING
                burstProgress.snapTo(0f)
                scaleAnim.snapTo(1f)
                breakProgress.snapTo(0f)
                breakProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 440, easing = FastOutSlowInEasing)
                )
                animMode = FavAnimMode.IDLE
            }
        }
    }

    // Extra canvas padding lets sparks and split heart halves animate without clipping
    val canvasSize = size * 1.65f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (animMode == FavAnimMode.FAVORITING) {
            val p = burstProgress.value
            Canvas(modifier = Modifier.size(canvasSize)) {
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                val maxR = this.size.minDimension * 0.46f

                // Soft expanding ring
                val ringAlpha = (1f - p).coerceIn(0f, 1f) * 0.42f
                if (ringAlpha > 0.01f) {
                    drawCircle(
                        color = activeTint.copy(alpha = ringAlpha),
                        radius = maxR * (0.38f + 0.56f * p),
                        center = Offset(cx, cy),
                        style = Stroke(width = (1.6f * (1f - p * 0.6f)).dp.toPx().coerceAtLeast(1f))
                    )
                }

                // 6 minimal radial micro-sparks
                val sparkAlpha = (1f - p).coerceIn(0f, 1f) * 0.85f
                val sparkRadius = (2.1f * (1f - p * 0.65f)).dp.toPx().coerceAtLeast(0.8f)
                val orbitR = maxR * (0.32f + 0.68f * p)
                for (i in 0 until 6) {
                    val angle = (i * 60.0 - 90.0) * PI / 180.0
                    val sx = cx + (cos(angle) * orbitR).toFloat()
                    val sy = cy + (sin(angle) * orbitR).toFloat()
                    drawCircle(
                        color = activeTint.copy(alpha = sparkAlpha),
                        radius = sparkRadius,
                        center = Offset(sx, sy)
                    )
                }
            }
        }

        if (animMode == FavAnimMode.UNFAVORITING) {
            val t = breakProgress.value
            // Outlined heart fades smoothly in behind the separating halves
            val outlineAlpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
            if (outlineAlpha > 0.01f) {
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = contentDescription,
                    tint = inactiveTint.copy(alpha = inactiveTint.alpha * outlineAlpha),
                    modifier = Modifier.size(size)
                )
            }

            // Breaking-heart split animation
            Canvas(modifier = Modifier.size(canvasSize)) {
                drawBreakingHeart(
                    progress = t,
                    heartColor = activeTint,
                    crackColor = Color.White.copy(alpha = 0.88f),
                    iconPx = size.toPx()
                )
            }
        } else {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = contentDescription,
                tint = if (isFavorite) activeTint else inactiveTint,
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                    }
            )
        }
    }
}

private fun DrawScope.drawBreakingHeart(
    progress: Float,
    heartColor: Color,
    crackColor: Color,
    iconPx: Float
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val hs = iconPx * 0.86f
    val halfW = hs * 0.50f
    val halfH = hs * 0.46f

    // Phase 1 (0..0.22): crack appears with subtle shake
    // Phase 2 (0.22..1.0): halves tilt outward, separate, drop slightly, and fade out
    val crackPhase = (progress / 0.22f).coerceIn(0f, 1f)
    val splitPhase = ((progress - 0.20f) / 0.80f).coerceIn(0f, 1f)
    val easedSplit = 1f - (1f - splitPhase) * (1f - splitPhase)

    val halfAlpha = (1f - splitPhase * 1.08f).coerceIn(0f, 1f)
    if (halfAlpha <= 0.01f) return

    val shakeX = if (progress < 0.22f) sin(progress * 55f) * (hs * 0.03f) else 0f
    val dx = easedSplit * hs * 0.24f
    val dy = easedSplit * hs * 0.20f
    val rot = easedSplit * 23f

    fun buildHeartPath(centerX: Float, centerY: Float): Path {
        val w = halfW
        val h = halfH
        return Path().apply {
            moveTo(centerX, centerY + h * 0.96f)
            cubicTo(
                centerX - w * 1.48f, centerY + h * 0.14f,
                centerX - w * 1.02f, centerY - h * 1.10f,
                centerX, centerY - h * 0.28f
            )
            cubicTo(
                centerX + w * 1.02f, centerY - h * 1.10f,
                centerX + w * 1.48f, centerY + h * 0.14f,
                centerX, centerY + h * 0.96f
            )
            close()
        }
    }

    // Jagged zigzag seam running down the center of the heart
    fun buildLeftClip(centerX: Float, centerY: Float): Path = Path().apply {
        moveTo(centerX - hs, centerY - hs)
        lineTo(centerX, centerY - hs)
        lineTo(centerX, centerY - halfH * 0.28f)
        lineTo(centerX - halfW * 0.18f, centerY + halfH * 0.04f)
        lineTo(centerX + halfW * 0.14f, centerY + halfH * 0.34f)
        lineTo(centerX - halfW * 0.08f, centerY + halfH * 0.64f)
        lineTo(centerX, centerY + hs)
        lineTo(centerX - hs, centerY + hs)
        close()
    }

    fun buildRightClip(centerX: Float, centerY: Float): Path = Path().apply {
        moveTo(centerX, centerY - hs)
        lineTo(centerX + hs, centerY - hs)
        lineTo(centerX + hs, centerY + hs)
        lineTo(centerX, centerY + hs)
        lineTo(centerX - halfW * 0.08f, centerY + halfH * 0.64f)
        lineTo(centerX + halfW * 0.14f, centerY + halfH * 0.34f)
        lineTo(centerX - halfW * 0.18f, centerY + halfH * 0.04f)
        lineTo(centerX, centerY - halfH * 0.28f)
        close()
    }

    val heartPath = buildHeartPath(cx + shakeX, cy)
    val leftClip = buildLeftClip(cx + shakeX, cy)
    val rightClip = buildRightClip(cx + shakeX, cy)

    // Left broken half
    withTransform({
        translate(left = -dx, top = dy)
        rotate(degrees = -rot, pivot = Offset(cx, cy + halfH * 0.35f))
    }) {
        clipPath(leftClip) {
            drawPath(path = heartPath, color = heartColor.copy(alpha = halfAlpha))
        }
    }

    // Right broken half
    withTransform({
        translate(left = dx, top = dy)
        rotate(degrees = rot, pivot = Offset(cx, cy + halfH * 0.35f))
    }) {
        clipPath(rightClip) {
            drawPath(path = heartPath, color = heartColor.copy(alpha = halfAlpha))
        }
    }

    // Brief crisp crack highlight during phase 1 before halves separate
    if (progress < 0.34f) {
        val crackAlpha = (crackPhase * (1f - splitPhase * 2.5f)).coerceIn(0f, 1f)
        if (crackAlpha > 0.02f) {
            val seamPath = Path().apply {
                moveTo(cx + shakeX, cy - halfH * 0.26f)
                lineTo(cx + shakeX - halfW * 0.18f, cy + halfH * 0.04f)
                lineTo(cx + shakeX + halfW * 0.14f, cy + halfH * 0.34f)
                lineTo(cx + shakeX - halfW * 0.08f, cy + halfH * 0.64f)
                lineTo(cx + shakeX, cy + halfH * 0.90f)
            }
            drawPath(
                path = seamPath,
                color = crackColor.copy(alpha = crackAlpha),
                style = Stroke(
                    width = (iconPx * 0.075f).coerceAtLeast(1.4f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
