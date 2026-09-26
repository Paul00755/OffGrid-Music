package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlin.math.roundToInt

data class EqPreset(
    val id: String,
    val name: String,
    val bands: List<Float>,
    val bassBoost: Int = 0,
    val virtualizer: Int = 0
)

val EQ_PRESETS = listOf(
    EqPreset("NORMAL", "Flat / Normal", listOf(0f, 0f, 0f, 0f, 0f), 0, 0),
    EqPreset("BASS_BOOST", "Bass Booster", listOf(0.50f, 0.30f, 0.05f, -0.05f, 0.05f), 500, 150),
    EqPreset("TREBLE_BOOST", "Treble Booster", listOf(-0.10f, 0.0f, 0.15f, 0.40f, 0.55f), 0, 100),
    EqPreset("VOCAL", "Vocal Booster", listOf(-0.15f, 0.10f, 0.45f, 0.25f, 0.10f), 0, 100),
    EqPreset("ROCK", "Rock", listOf(0.40f, 0.20f, -0.10f, 0.25f, 0.45f), 350, 200),
    EqPreset("POP", "Pop", listOf(-0.10f, 0.15f, 0.35f, 0.20f, -0.05f), 150, 150),
    EqPreset("JAZZ", "Jazz", listOf(0.25f, 0.15f, -0.10f, 0.20f, 0.30f), 200, 200),
    EqPreset("ELECTRONIC", "Electronic", listOf(0.45f, 0.25f, 0.0f, 0.25f, 0.45f), 450, 300),
    EqPreset("CLASSICAL", "Classical", listOf(0.30f, 0.15f, -0.10f, 0.20f, 0.35f), 0, 250),
    EqPreset("HIP_HOP", "Hip-Hop", listOf(0.55f, 0.35f, 0.05f, 0.15f, 0.40f), 600, 150),
    EqPreset("ACOUSTIC", "Acoustic", listOf(0.20f, 0.10f, 0.10f, 0.25f, 0.30f), 100, 150)
)

val FREQUENCY_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

@Composable
fun EqualizerVisualizer(
    currentPreset: String,
    customBands: List<Float>,
    bassBoostStrength: Int,
    virtualizerStrength: Int,
    onPresetChange: (String) -> Unit,
    onBandsChange: (List<Float>) -> Unit,
    onBassBoostChange: (Int) -> Unit,
    onVirtualizerChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bands by remember(customBands) {
        mutableStateOf(if (customBands.size >= 5) customBands.take(5) else listOf(0f, 0f, 0f, 0f, 0f))
    }
    var selectedPresetId by remember(currentPreset) { mutableStateOf(currentPreset) }

    LaunchedEffect(currentPreset) {
        selectedPresetId = currentPreset
    }
    LaunchedEffect(customBands) {
        if (customBands.size >= 5) {
            bands = customBands.take(5)
        }
    }

    val primaryCol = BentoPrimary
    val darkPurpleCol = BentoCardDarkPurple
    val cyanCol = Color(0xFF00E5FF)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BentoCardSurface)
            .border(1.dp, BentoBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(primaryCol.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = null,
                        tint = primaryCol,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Master Audio Equalizer",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    val activePreset = EQ_PRESETS.find { it.id.equals(selectedPresetId, ignoreCase = true) }
                    val labelText = when {
                        selectedPresetId.equals("CUSTOM", ignoreCase = true) -> "Custom Active"
                        selectedPresetId.equals("NORMAL", ignoreCase = true) -> "Default (Flat) Active"
                        activePreset != null -> "${activePreset.name} Active"
                        else -> "${selectedPresetId} Active"
                    }
                    Text(
                        text = labelText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = primaryCol
                    )
                }
            }

            IconButton(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    val flatBands = listOf(0f, 0f, 0f, 0f, 0f)
                    bands = flatBands
                    selectedPresetId = "NORMAL"
                    onPresetChange("NORMAL")
                },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(BentoCardPurple)
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = "Reset EQ",
                    tint = BentoTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Preset Chips Carousel
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            if (selectedPresetId.equals("CUSTOM", ignoreCase = true)) {
                item(key = "custom_active_chip") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = primaryCol,
                        border = androidx.compose.foundation.BorderStroke(1.dp, primaryCol),
                        modifier = Modifier
                    ) {
                        Text(
                            text = "Custom",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoOnPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            items(EQ_PRESETS, key = { it.id }) { preset ->
                val isSelected = selectedPresetId.equals(preset.id, ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) primaryCol else BentoCardPurple,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) primaryCol else BentoBorder
                    ),
                    modifier = Modifier.clickable {
                        HapticUtils.performClickHaptic(context)
                        selectedPresetId = preset.id
                        bands = preset.bands
                        onPresetChange(preset.id)
                    }
                ) {
                    Text(
                        text = preset.name,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) BentoOnPrimary else BentoTextPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // Sleek Vertical Lines Equalizer Graph
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BentoCardPurple.copy(alpha = 0.6f))
                .border(1.dp, BentoBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bands) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val width = size.width
                            val height = size.height
                            if (width > 0 && height > 0) {
                                val touchX = change.position.x
                                val touchY = change.position.y
                                val bandWidth = width / 5f
                                val bandIdx = ((touchX / bandWidth).toInt()).coerceIn(0, 4)

                                // Normalize Y: middle (height/2) is 0dB, top (20) is +1.0, bottom (height - 20) is -1.0
                                val midY = height / 2f
                                val usableHalfHeight = (height - 40f) / 2f
                                val normLevel = ((midY - touchY) / usableHalfHeight).coerceIn(-1.0f, 1.0f)

                                val updated = bands.toMutableList()
                                updated[bandIdx] = normLevel
                                bands = updated
                                selectedPresetId = "CUSTOM"
                                onPresetChange("CUSTOM")
                                onBandsChange(updated)
                            }
                        }
                    }
                    .pointerInput(bands) {
                        detectTapGestures { offset ->
                            val width = size.width
                            val height = size.height
                            if (width > 0 && height > 0) {
                                val touchX = offset.x
                                val touchY = offset.y
                                val bandWidth = width / 5f
                                val bandIdx = ((touchX / bandWidth).toInt()).coerceIn(0, 4)

                                val midY = height / 2f
                                val usableHalfHeight = (height - 40f) / 2f
                                val normLevel = ((midY - touchY) / usableHalfHeight).coerceIn(-1.0f, 1.0f)

                                val updated = bands.toMutableList()
                                updated[bandIdx] = normLevel
                                bands = updated
                                selectedPresetId = "CUSTOM"
                                onPresetChange("CUSTOM")
                                onBandsChange(updated)
                            }
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val midY = h / 2f
                val usableHalfHeight = (h - 40f) / 2f

                // Draw Horizontal Zero Reference and Grid Lines
                drawLine(
                    color = primaryCol.copy(alpha = 0.25f),
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1.5f
                )

                // Calculate Point Offsets for 5 Bands
                val points = bands.mapIndexed { idx, level ->
                    val x = (idx + 0.5f) * (w / 5f)
                    val y = midY - (level * usableHalfHeight)
                    Offset(x, y.coerceIn(12f, h - 12f))
                }

                // 1. Draw Sleek Vertical Lines/Tracks for each band
                bands.forEachIndexed { idx, level ->
                    val centerX = (idx + 0.5f) * (w / 5f)
                    val trackTop = midY - usableHalfHeight
                    val trackBottom = midY + usableHalfHeight
                    val nodeY = points[idx].y

                    // Inactive Background Track Line
                    drawLine(
                        color = Color.White.copy(alpha = 0.10f),
                        start = Offset(centerX, trackTop),
                        end = Offset(centerX, trackBottom),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round
                    )

                    // Active Glowing Fill Bar from 0dB (midY) to current nodeY
                    val activeBarTop = minOf(midY, nodeY)
                    val activeBarBottom = maxOf(midY, nodeY)
                    val activeHeight = activeBarBottom - activeBarTop

                    if (activeHeight > 1f) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(primaryCol, cyanCol),
                                startY = activeBarTop,
                                endY = activeBarBottom
                            ),
                            topLeft = Offset(centerX - 4f, activeBarTop),
                            size = Size(8f, activeHeight),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                    }
                }

                // 2. Build Smooth Bezier Path across nodes
                val curvePath = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 0 until points.size - 1) {
                        val p0 = points[i]
                        val p1 = points[i + 1]
                        val controlX1 = (p0.x + p1.x) / 2f
                        cubicTo(controlX1, p0.y, controlX1, p1.y, p1.x, p1.y)
                    }
                }

                // Closed Area Gradient under curve
                val fillPath = Path().apply {
                    addPath(curvePath)
                    lineTo(points.last().x, h)
                    lineTo(points.first().x, h)
                    close()
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryCol.copy(alpha = 0.28f),
                            primaryCol.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = h
                    )
                )

                // Smooth Curve Line
                drawPath(
                    path = curvePath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(primaryCol, cyanCol, primaryCol)
                    ),
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )

                // 3. Draw Tactile Sleek Control Nodes on each vertical line
                points.forEachIndexed { _, pt ->
                    // Outer glow ring
                    drawCircle(
                        color = primaryCol.copy(alpha = 0.25f),
                        radius = 20f,
                        center = pt
                    )
                    // Mid node
                    drawCircle(
                        color = primaryCol,
                        radius = 12f,
                        center = pt
                    )
                    // Inner bright core
                    drawCircle(
                        color = Color.White,
                        radius = 5f,
                        center = pt
                    )
                }
            }

            // dB scale markers on the left
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .align(Alignment.CenterStart),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("+10dB", fontSize = 9.sp, color = BentoTextSubtle, fontWeight = FontWeight.Bold)
                Text("0dB", fontSize = 9.sp, color = primaryCol.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                Text("-10dB", fontSize = 9.sp, color = BentoTextSubtle, fontWeight = FontWeight.Bold)
            }
        }

        // Frequency Labels and dB Values Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            bands.forEachIndexed { idx, level ->
                val freqLabel = FREQUENCY_LABELS.getOrElse(idx) { "" }
                val dbValue = (level * 10f).roundToInt()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (dbValue > 0) "+${dbValue}dB" else "${dbValue}dB",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dbValue != 0) primaryCol else BentoTextSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = freqLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextSubtle
                    )
                }
            }
        }

        Divider(color = BentoBorder)

        // Bass Boost & 3D Surround Sliders
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Bass Boost Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BentoCardPurple)
                    .border(1.dp, BentoBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = primaryCol,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Bass Boost", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                    }
                    Text(
                        text = "${(bassBoostStrength / 10)}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryCol
                    )
                }

                Slider(
                    value = bassBoostStrength.toFloat(),
                    onValueChange = {
                        onBassBoostChange(it.toInt())
                        selectedPresetId = "CUSTOM"
                        onPresetChange("CUSTOM")
                    },
                    valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(
                        thumbColor = primaryCol,
                        activeTrackColor = primaryCol,
                        inactiveTrackColor = BentoCardDarkPurple
                    )
                )
            }

            // 3D Virtualizer Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BentoCardPurple)
                    .border(1.dp, BentoBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SurroundSound,
                            contentDescription = null,
                            tint = cyanCol,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("3D Surround", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                    }
                    Text(
                        text = "${(virtualizerStrength / 10)}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = cyanCol
                    )
                }

                Slider(
                    value = virtualizerStrength.toFloat(),
                    onValueChange = {
                        onVirtualizerChange(it.toInt())
                        selectedPresetId = "CUSTOM"
                        onPresetChange("CUSTOM")
                    },
                    valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(
                        thumbColor = cyanCol,
                        activeTrackColor = cyanCol,
                        inactiveTrackColor = BentoCardDarkPurple
                    )
                )
            }
        }
    }
}
