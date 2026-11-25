package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Custom vector-drawn circular seek icon matching the double-chevron arc + centered interval
 * number style (supports 5s, 10s, 15s, 30s).
 *
 * When [isForward] is false (backward), the arc opens at top-left with a counter-clockwise
 * double-chevron arrowhead. When [isForward] is true (forward), the arc and chevrons are
 * horizontally mirrored to point clockwise at top-right while the centered number remains upright.
 */
@Composable
fun SeekIntervalIcon(
    seconds: Int,
    isForward: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 28.dp
) {
    val label = seconds.coerceIn(1, 99).toString()
    val fontSizeSp = remember(iconSize, label.length) {
        val base = iconSize.value * if (label.length == 1) 0.42f else 0.355f
        base.coerceIn(7.5f, 18f).sp
    }

    Box(
        modifier = modifier.size(iconSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDim = size.minDimension
            val strokeWidth = (minDim * 0.095f).coerceAtLeast(1.8f)
            val inset = strokeWidth * 1.15f
            val arcDiameter = minDim - inset * 2f
            val topLeft = Offset(
                (size.width - arcDiameter) / 2f,
                (size.height - arcDiameter) / 2f
            )
            val arcSize = Size(arcDiameter, arcDiameter)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = arcDiameter / 2f

            withTransform({
                if (isForward) {
                    scale(scaleX = -1f, scaleY = 1f, pivot = Offset(cx, cy))
                }
            }) {
                // Main circular arc: starts near 10:30 (226 deg) and sweeps clockwise ~294 deg to ~160 deg
                // leaving a clean opening at upper-left for the stacked double-chevron arrowhead.
                drawArc(
                    color = tint,
                    startAngle = 226f,
                    sweepAngle = 304f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round
                    )
                )

                // Inner arrowhead attached to the arc start (top-left, pointing down-left)
                // Coordinates expressed relative to center (cx, cy) and radius r for crisp scaling.
                val headTipX = cx - r * 0.72f
                val headTipY = cy - r * 0.68f
                val legLen = r * 0.34f

                val primaryChevron = Path().apply {
                    moveTo(headTipX + legLen * 0.16f, headTipY - legLen * 0.92f)
                    lineTo(headTipX, headTipY)
                    lineTo(headTipX + legLen * 0.96f, headTipY + legLen * 0.16f)
                }
                drawPath(
                    path = primaryChevron,
                    color = tint,
                    style = Stroke(
                        width = strokeWidth * 1.12f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Outer parallel chevron slightly down-left, matching the stacked double-arrow silhouette
                val offsetX = -r * 0.25f
                val offsetY = r * 0.23f
                val outerTipX = headTipX + offsetX
                val outerTipY = headTipY + offsetY
                val outerLeg = legLen * 0.92f

                val secondaryChevron = Path().apply {
                    moveTo(outerTipX + outerLeg * 0.08f, outerTipY - outerLeg * 0.95f)
                    lineTo(outerTipX, outerTipY)
                    lineTo(outerTipX + outerLeg * 0.95f, outerTipY + outerLeg * 0.08f)
                }
                drawPath(
                    path = secondaryChevron,
                    color = tint,
                    style = Stroke(
                        width = strokeWidth * 1.02f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        Text(
            text = label,
            color = tint,
            style = TextStyle(
                fontSize = fontSizeSp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 1
        )
    }
}

/**
 * Accessible 48dp touch-target seek button with subtle spring press feedback and haptics.
 */
@Composable
fun SeekIntervalButton(
    seconds: Int,
    isForward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 26.dp,
    touchSize: Dp = 48.dp,
    testTagName: String = if (isForward) "now_playing_seek_forward_button" else "now_playing_seek_backward_button"
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.86f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "seek_btn_scale"
    )
    val description = if (isForward) {
        "Seek forward $seconds seconds"
    } else {
        "Seek backward $seconds seconds"
    }

    Box(
        modifier = modifier
            .size(touchSize)
            .clip(CircleShape)
            .testTag(testTagName)
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = tint.copy(alpha = 0.28f)),
                role = Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        SeekIntervalIcon(
            seconds = seconds,
            isForward = isForward,
            tint = tint,
            iconSize = iconSize * scale
        )
    }
}
