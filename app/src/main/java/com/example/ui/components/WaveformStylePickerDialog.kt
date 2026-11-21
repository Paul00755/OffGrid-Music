package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import kotlin.math.sin

data class WaveformStyleOption(
    val id: String,
    val label: String,
    val aliases: Set<String> = emptySet()
)

val ALL_WAVEFORM_STYLE_OPTIONS: List<WaveformStyleOption> = listOf(
    WaveformStyleOption("SLEEK_LINE", "Sleek Line", setOf("SLEEK", "MINIMAL", "LINE")),
    WaveformStyleOption("SNAKE", "Snake Sine", setOf("CURVED")),
    WaveformStyleOption("BARS", "Symmetric", setOf("SYMMETRIC_CAPSULES", "CAPSULE", "SMOOTH_WAVE")),
    WaveformStyleOption("DOTS", "Dotted", setOf("CIRCLE", "DOTTED_CURVES", "CIRCULAR")),
    WaveformStyleOption("SQUIGGLY", "Squiggly", setOf("SQUIGGLE")),
    WaveformStyleOption("WAVY", "Wave", setOf("WAVE")),
    WaveformStyleOption("BAR", "Bar", setOf("PILL_BAR"))
)

fun normalizeWaveformStyleId(rawStyle: String): String {
    val upper = rawStyle.trim().uppercase()
    return ALL_WAVEFORM_STYLE_OPTIONS.firstOrNull { opt ->
        opt.id == upper || upper in opt.aliases
    }?.id ?: "SNAKE"
}

fun waveformStyleDisplayName(rawStyle: String): String {
    val normalized = normalizeWaveformStyleId(rawStyle)
    return ALL_WAVEFORM_STYLE_OPTIONS.firstOrNull { it.id == normalized }?.label ?: "Snake Sine"
}

/**
 * Miniature Canvas visual representation for any of the 7 waveform seeker styles,
 * used in Settings rows, the 2-column WaveformStylePickerDialog, and the Live Layout Studio.
 */
@Composable
fun WaveformMiniPreviewCanvas(
    styleId: String,
    accentColor: Color = BentoPrimary,
    trackColor: Color = BentoTextPrimary.copy(alpha = 0.22f),
    progressFraction: Float = 0.56f,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(34.dp)
) {
    val normalized = remember(styleId) { normalizeWaveformStyleId(styleId) }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val activeX = (w * progressFraction).coerceIn(w * 0.2f, w * 0.85f)

        when (normalized) {
            "SLEEK_LINE" -> {
                val stroke = (h * 0.16f).coerceIn(2.5.dp.toPx(), 5.dp.toPx())
                drawLine(
                    color = trackColor,
                    start = Offset(2.dp.toPx(), cy),
                    end = Offset(w - 2.dp.toPx(), cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = accentColor,
                    start = Offset(2.dp.toPx(), cy),
                    end = Offset(activeX, cy),
                    strokeWidth = stroke * 1.15f,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = accentColor,
                    radius = (h * 0.16f).coerceIn(3.5.dp.toPx(), 5.5.dp.toPx()),
                    center = Offset(activeX, cy)
                )
            }

            "SNAKE" -> {
                val stroke = (h * 0.11f).coerceIn(2.2.dp.toPx(), 3.6.dp.toPx())
                val amp = (h * 0.22f).coerceIn(3.dp.toPx(), 6.5.dp.toPx())
                drawLine(
                    color = trackColor,
                    start = Offset(activeX, cy),
                    end = Offset(w - 2.dp.toPx(), cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                path.rewind()
                val steps = 48
                val startX = 2.dp.toPx()
                val span = (activeX - startX).coerceAtLeast(1f)
                for (i in 0..steps) {
                    val t = i.toFloat() / steps.toFloat()
                    val x = startX + t * span
                    val env = sin(t * Math.PI).toFloat()
                    val y = cy + sin(t * 6.0 * Math.PI).toFloat() * amp * env
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, accentColor, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val thumbW = (h * 0.12f).coerceIn(2.5.dp.toPx(), 4.dp.toPx())
                val thumbH = (h * 0.56f).coerceIn(10.dp.toPx(), 18.dp.toPx())
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(activeX - thumbW / 2f, cy - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f)
                )
            }

            "BARS" -> {
                val count = 18
                val step = (w - 4.dp.toPx()) / count.toFloat()
                val barW = (step * 0.52f).coerceIn(2.dp.toPx(), 4.dp.toPx())
                for (i in 0 until count) {
                    val x = 2.dp.toPx() + i * step + step / 2f
                    val t = i.toFloat() / (count - 1).toFloat()
                    val env = sin(t * Math.PI).toFloat()
                    val mod = 0.28f + 0.72f * env * (0.65f + 0.35f * kotlin.math.abs(sin(i * 1.35f)))
                    val barH = (h * 0.72f) * mod
                    drawLine(
                        color = if (x <= activeX) accentColor else trackColor,
                        start = Offset(x, cy - barH / 2f),
                        end = Offset(x, cy + barH / 2f),
                        strokeWidth = barW,
                        cap = StrokeCap.Round
                    )
                }
            }

            "DOTS" -> {
                val count = 13
                val step = (w - 6.dp.toPx()) / count.toFloat()
                val amp = h * 0.24f
                for (i in 0 until count) {
                    val x = 3.dp.toPx() + i * step + step / 2f
                    val t = i.toFloat() / (count - 1).toFloat()
                    val env = sin(t * Math.PI).toFloat()
                    val y = cy + sin(i * 0.75f) * amp * (0.35f + 0.65f * env)
                    val isActive = x <= activeX
                    drawCircle(
                        color = if (isActive) accentColor else trackColor,
                        radius = if (isActive) (h * 0.11f).coerceIn(2.4.dp.toPx(), 3.8.dp.toPx()) else (h * 0.08f).coerceIn(1.8.dp.toPx(), 2.8.dp.toPx()),
                        center = Offset(x, y)
                    )
                }
            }

            "SQUIGGLY" -> {
                val stroke = (h * 0.125f).coerceIn(2.6.dp.toPx(), 4.2.dp.toPx())
                val amp = (h * 0.19f).coerceIn(3.dp.toPx(), 5.5.dp.toPx())
                val thumbW = (h * 0.11f).coerceIn(2.8.dp.toPx(), 4.2.dp.toPx())
                val thumbH = (h * 0.56f).coerceIn(12.dp.toPx(), 20.dp.toPx())
                val startX = 3.dp.toPx()
                val endX = w - 3.dp.toPx()
                val waveEnd = (activeX - thumbW * 0.45f).coerceAtLeast(startX + 4f)

                drawLine(
                    color = trackColor,
                    start = Offset((activeX + thumbW * 0.5f).coerceAtMost(endX), cy),
                    end = Offset(endX, cy),
                    strokeWidth = stroke * 0.85f,
                    cap = StrokeCap.Round
                )

                path.rewind()
                val steps = 48
                val span = (waveEnd - startX).coerceAtLeast(1f)
                for (i in 0..steps) {
                    val t = i.toFloat() / steps.toFloat()
                    val x = startX + t * span
                    val edgeEnv = minOf((t * 8f).coerceAtMost(1f), ((1f - t) * 8f).coerceAtMost(1f))
                    val y = cy + sin(t * 5.0 * Math.PI).toFloat() * amp * (0.3f + 0.7f * edgeEnv)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, accentColor, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))

                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(activeX - thumbW / 2f, cy - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f)
                )
            }

            "WAVY" -> {
                val stroke = (h * 0.115f).coerceIn(2.4.dp.toPx(), 3.8.dp.toPx())
                val amp = (h * 0.18f).coerceIn(3.dp.toPx(), 5.2.dp.toPx())
                val knobR = (h * 0.21f).coerceIn(4.5.dp.toPx(), 7.dp.toPx())
                val startX = 3.dp.toPx()
                val endX = w - 4.dp.toPx()
                val lineStart = (activeX + knobR + 3.dp.toPx()).coerceAtMost(endX)

                if (lineStart < endX) {
                    drawLine(
                        color = trackColor,
                        start = Offset(lineStart, cy),
                        end = Offset(endX, cy),
                        strokeWidth = stroke * 0.9f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = accentColor.copy(alpha = 0.88f),
                        radius = 1.8.dp.toPx(),
                        center = Offset(endX, cy)
                    )
                }

                path.rewind()
                val waveEnd = (activeX - knobR * 0.55f).coerceAtLeast(startX + 4f)
                val span = (waveEnd - startX).coerceAtLeast(1f)
                val steps = 42
                for (i in 0..steps) {
                    val t = i.toFloat() / steps.toFloat()
                    val x = startX + t * span
                    val endFade = ((1f - t) * 4.5f).coerceAtMost(1f)
                    val y = cy + sin(t * 4.0 * Math.PI).toFloat() * amp * (0.25f + 0.75f * endFade)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, accentColor, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(color = accentColor, radius = knobR, center = Offset(activeX, cy))
            }

            "BAR" -> {
                val barH = (h * 0.36f).coerceIn(8.dp.toPx(), 13.dp.toPx())
                val barR = barH / 2f
                val divW = (h * 0.11f).coerceIn(2.6.dp.toPx(), 3.8.dp.toPx())
                val divH = (h * 0.72f).coerceIn(16.dp.toPx(), 24.dp.toPx())
                val gap = 3.5.dp.toPx()
                val startX = 2.dp.toPx()
                val endX = w - 2.dp.toPx()
                val divX = activeX.coerceIn(startX + barH + gap, endX - barH - gap)

                val playedW = (divX - divW / 2f - gap - startX).coerceAtLeast(barH)
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(startX, cy - barR),
                    size = Size(playedW, barH),
                    cornerRadius = CornerRadius(barR, barR)
                )

                val unplayedStart = divX + divW / 2f + gap
                val unplayedW = (endX - unplayedStart).coerceAtLeast(barH)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(unplayedStart, cy - barR),
                    size = Size(unplayedW, barH),
                    cornerRadius = CornerRadius(barR, barR)
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.88f),
                    radius = 1.9.dp.toPx(),
                    center = Offset(endX - barR, cy)
                )

                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(divX - divW / 2f, cy - divH / 2f),
                    size = Size(divW, divH),
                    cornerRadius = CornerRadius(divW / 2f, divW / 2f)
                )
            }
        }
    }
}

/**
 * Visual 2-column waveform style picker dialog inspired by Screenshot 7,
 * presenting all 7 waveform styles (Sleek Line, Snake Sine, Symmetric, Dotted,
 * Squiggly, Wave, Bar) with live Canvas previews.
 */
@Composable
fun WaveformStylePickerDialog(
    currentStyle: String,
    onSelectStyle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val activeId = remember(currentStyle) { normalizeWaveformStyleId(currentStyle) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp)
                .testTag("waveform_style_picker_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Waveform & Seekbar Style",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Choose how the playback progress bar looks across Now Playing & Home",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // 2-column grid of visual preview cards
                val rows = remember { ALL_WAVEFORM_STYLE_OPTIONS.chunked(2) }
                rows.forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        pair.forEach { option ->
                            val isSelected = option.id == activeId
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (isSelected) BentoPrimary.copy(alpha = 0.14f) else BentoCardPurple.copy(alpha = 0.65f),
                                border = BorderStroke(
                                    width = if (isSelected) 1.75.dp else 1.dp,
                                    color = if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.55f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(18.dp))
                                    .testTag("waveform_option_${option.id.lowercase()}")
                                    .clickable {
                                        onSelectStyle(option.id)
                                        onDismiss()
                                    }
                            ) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = BentoPrimary,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(top = 8.dp, end = 8.dp)
                                                .size(16.dp)
                                        )
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        WaveformMiniPreviewCanvas(
                                            styleId = option.id,
                                            accentColor = BentoPrimary,
                                            trackColor = BentoTextPrimary.copy(alpha = 0.24f),
                                            progressFraction = if (option.id == "BAR") 0.38f else 0.56f,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(34.dp)
                                        )
                                        Text(
                                            text = option.label,
                                            fontSize = 12.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) BentoPrimary else BentoTextPrimary,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = CircleShape,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("waveform_picker_cancel")
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoPrimary
                        )
                    }
                }
            }
        }
    }
}
