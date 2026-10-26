package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BentoPrimary
import kotlin.math.sin

@Composable
fun NowPlayingWaveformVisualizer(
    trackId: Long? = null,
    progressMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    style: String = "SNAKE",
    accentColor: Color = BentoPrimary,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val waveformColor = if (isDarkTheme) Color.White else Color.Black
    val inactiveTrackColor = waveformColor.copy(alpha = 0.22f)

    val actualProgress = remember(trackId, progressMs, durationMs) {
        if (durationMs > 0) (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    }

    var isDragging by remember(trackId) { mutableStateOf(false) }
    var dragProgress by remember(trackId) { mutableFloatStateOf(0f) }

    val displayProgress = if (isDragging) dragProgress else actualProgress

    val activePhase = if (isPlaying && !isDragging) {
        val infiniteTransition = rememberInfiniteTransition(label = "waveform")
        val phase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )
        phase
    } else {
        0f
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(trackId, durationMs) {
                detectTapGestures { offset ->
                    if (size.width > 0 && durationMs > 0) {
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeekTo((fraction * durationMs).toLong())
                    }
                }
            }
            .pointerInput(trackId, durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        if (size.width > 0) {
                            dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (size.width > 0) {
                            val newProgress = dragProgress + (dragAmount.x / size.width.toFloat())
                            dragProgress = newProgress.coerceIn(0f, 1f)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        if (durationMs > 0) {
                            onSeekTo((dragProgress * durationMs).toLong())
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val activeWidth = (width * displayProgress).coerceIn(0f, width)

        val trackStroke = 4.dp.toPx() // ~14px thick horizontal line
        val waveAmplitude = 6.dp.toPx() // ~24-28px peak-to-peak wave height
        val thumbWidth = 6.dp.toPx() // ~18px wide
        val thumbHeight = 24.dp.toPx() // ~72px high vertical pill

        when (style.uppercase()) {
            "SLEEK_LINE", "SLEEK", "LINE", "MINIMAL" -> {
                // Background Track
                drawLine(
                    color = inactiveTrackColor,
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                // Active Played Line
                if (activeWidth > 0f) {
                    drawLine(
                        color = accentColor,
                        start = Offset(0f, centerY),
                        end = Offset(activeWidth, centerY),
                        strokeWidth = 4.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                // Ambient Seeker Glow
                drawCircle(
                    color = accentColor.copy(alpha = 0.35f),
                    radius = 10.dp.toPx(),
                    center = Offset(activeWidth, centerY)
                )
                // Seeker Head Thumb
                drawCircle(
                    color = waveformColor,
                    radius = 6.dp.toPx(),
                    center = Offset(activeWidth, centerY)
                )
            }

            "SNAKE", "CURVED" -> {
                // 1. Remaining unplayed background track line (only drawn from current position to end, hiding it under the active wave)
                if (activeWidth < width) {
                    val inactiveStart = activeWidth.coerceAtLeast(0f)
                    drawLine(
                        color = inactiveTrackColor,
                        start = Offset(inactiveStart, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = trackStroke,
                        cap = StrokeCap.Round
                    )
                }

                // 2. Continuous smooth sine waveform for played portion
                if (activeWidth > 0f) {
                    val pathActive = Path()
                    val steps = (activeWidth / 2f).toInt().coerceIn(40, 200)
                    val dx = activeWidth / steps.toFloat()
                    
                    // ~7-8 smooth wave cycles across played portion
                    val cycleFreq = (8.0 * Math.PI) / activeWidth.toDouble()

                    pathActive.moveTo(0f, centerY)

                    for (i in 0..steps) {
                        val x = (i * dx).coerceAtMost(activeWidth)
                        val progressRatio = (x / activeWidth).coerceIn(0f, 1f)
                        // Smooth envelope factor so wave starts at centerY (x=0) and ends at centerY (x=activeWidth)
                        val envelope = kotlin.math.sin(progressRatio * Math.PI).toFloat()

                        val waveVal = sin(x.toDouble() * cycleFreq + activePhase.toDouble()).toFloat()
                        val y = centerY + (waveVal * waveAmplitude * envelope)

                        if (i == 0) {
                            pathActive.moveTo(x, y)
                        } else {
                            pathActive.lineTo(x, y)
                        }
                    }

                    drawPath(
                        path = pathActive,
                        color = waveformColor,
                        style = Stroke(
                            width = trackStroke,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 3. Playback Position Marker: Vertical rounded pill (centered on current playback position)
                drawRoundRect(
                    color = waveformColor,
                    topLeft = Offset(activeWidth - (thumbWidth / 2f), centerY - (thumbHeight / 2f)),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )
            }

            "SYMMETRIC_CAPSULES", "CAPSULE", "BARS", "SMOOTH_WAVE" -> {
                val barCount = 52
                val barWidth = (width / (barCount * 1.4f)).coerceIn(2.5.dp.toPx(), 6.dp.toPx())
                val stepX = width / barCount.toFloat()

                for (i in 0 until barCount) {
                    val x = i * stepX + (stepX / 2f)
                    val isActive = x <= activeWidth

                    val normPos = i.toFloat() / (barCount - 1).toFloat()
                    val envelope = kotlin.math.sin(normPos * Math.PI).toFloat()
                    val microvibe = if (isPlaying) (sin(i * 0.45f + activePhase).toFloat() * 0.18f) else 0f
                    val factor = (0.15f + 0.85f * envelope + microvibe).coerceIn(0.12f, 1.0f)
                    val barH = (height * 0.88f) * factor

                    val top = centerY - (barH / 2f)
                    val bottom = centerY + (barH / 2f)

                    drawLine(
                        color = if (isActive) accentColor else inactiveTrackColor,
                        start = Offset(x, top),
                        end = Offset(x, bottom),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                }
            }

            "CLASSIC", "EQUALIZER", "BARS_CLASSIC" -> {
                val barCount = 42
                val barWidth = (width / (barCount * 1.5f)).coerceIn(2.5.dp.toPx(), 5.dp.toPx())
                val stepX = width / barCount.toFloat()
                val maxBarH = height * 0.85f

                for (i in 0 until barCount) {
                    val x = i * stepX + (stepX / 2f)
                    val isActive = x <= activeWidth

                    // Classic equalizer bouncing frequency spectrum effect
                    val freq1 = sin(i * 0.65f + activePhase * 1.4f).toFloat()
                    val freq2 = sin(i * 1.2f - activePhase * 0.8f).toFloat()
                    val bounce = if (isPlaying) ((freq1 * 0.4f + freq2 * 0.3f) + 0.3f).coerceIn(0.15f, 0.95f) else 0.25f

                    val barH = maxBarH * bounce
                    val bottomY = height - 4.dp.toPx()
                    val topY = bottomY - barH

                    drawLine(
                        color = if (isActive) accentColor else inactiveTrackColor,
                        start = Offset(x, bottomY),
                        end = Offset(x, topY),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                }
            }

            "DOTS", "DOTTED_CURVES", "CIRCLE", "CIRCULAR" -> {
                val dotCount = 36
                val dx = width / dotCount
                val maxWaveAmp = height * 0.38f

                for (i in 0 until dotCount) {
                    val x = i * dx + dx / 2f
                    val isActive = x <= activeWidth
                    val progressRatio = (x / width).coerceIn(0f, 1f)
                    val envelope = kotlin.math.sin(progressRatio * Math.PI).toFloat()

                    val waveOffset = sin(i * 0.45f + activePhase.toDouble()).toFloat() * maxWaveAmp * (0.35f + 0.65f * envelope)
                    val y = centerY + (if (isPlaying) waveOffset else (sin(i * 0.35f).toFloat() * maxWaveAmp * 0.4f * envelope))

                    val baseRadius = if (isActive) 4.5.dp.toPx() else 2.8.dp.toPx()
                    val pulseFactor = if (isActive && isPlaying) (1f + 0.25f * sin(i * 0.8f + activePhase * 2f).toFloat()) else 1f
                    val r = baseRadius * pulseFactor

                    if (isActive) {
                        // Outer halo glow
                        drawCircle(
                            color = accentColor.copy(alpha = 0.25f),
                            radius = r * 1.8f,
                            center = Offset(x, y)
                        )
                        // Core dot
                        drawCircle(
                            color = accentColor,
                            radius = r,
                            center = Offset(x, y)
                        )
                        // Inner bright dot
                        drawCircle(
                            color = waveformColor,
                            radius = r * 0.4f,
                            center = Offset(x, y)
                        )
                    } else {
                        // Clean inactive dotted wave track
                        drawCircle(
                            color = inactiveTrackColor,
                            radius = r,
                            center = Offset(x, y)
                        )
                    }
                }

                // Seeker head node
                val seekerProgressRatio = (activeWidth / width).coerceIn(0f, 1f)
                val seekerEnvelope = kotlin.math.sin(seekerProgressRatio * Math.PI).toFloat()
                val seekerWaveOffset = sin((activeWidth / dx) * 0.45f + activePhase.toDouble()).toFloat() * maxWaveAmp * (0.35f + 0.65f * seekerEnvelope)
                val seekerY = centerY + (if (isPlaying) seekerWaveOffset else 0f)

                drawCircle(
                    color = accentColor.copy(alpha = 0.35f),
                    radius = 9.dp.toPx(),
                    center = Offset(activeWidth, seekerY)
                )
                drawCircle(
                    color = waveformColor,
                    radius = 6.dp.toPx(),
                    center = Offset(activeWidth, seekerY)
                )
            }

            else -> { // MINIMAL
                drawLine(
                    color = inactiveTrackColor,
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = accentColor,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = waveformColor,
                    radius = 6.dp.toPx(),
                    center = Offset(activeWidth, centerY)
                )
            }
        }
    }
}

