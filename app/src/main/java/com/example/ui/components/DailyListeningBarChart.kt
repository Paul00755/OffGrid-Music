package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DailyListeningStat
import com.example.ui.theme.*

@Composable
fun DailyListeningBarChart(
    dailyStats: List<DailyListeningStat>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
    showSummaryHeader: Boolean = true,
    accentColor: Color = BentoPrimary,
    onDaySelected: ((DailyListeningStat) -> Unit)? = null
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    // Auto-select today by default or the day with most plays
    LaunchedEffect(dailyStats) {
        if (dailyStats.isNotEmpty()) {
            val todayIdx = dailyStats.indexOfFirst { it.isToday }
            selectedIndex = if (todayIdx != -1) todayIdx else dailyStats.lastIndex
        }
    }

    val selectedStat = selectedIndex?.let { dailyStats.getOrNull(it) }

    val total7DayDurationMs = remember(dailyStats) {
        dailyStats.sumOf { it.durationMs }
    }

    val maxDurationMs = remember(dailyStats) {
        val max = dailyStats.maxOfOrNull { it.durationMs } ?: 0L
        // At least 15 mins for nice scale
        maxOf(max, 15 * 60 * 1000L)
    }

    val peakDay = remember(dailyStats) {
        dailyStats.maxByOrNull { it.durationMs }
    }

    val averageDailyMs = remember(dailyStats, total7DayDurationMs) {
        if (dailyStats.isNotEmpty()) total7DayDurationMs / dailyStats.size else 0L
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showSummaryHeader) {
            // Selected or Active Day Summary Banner
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BentoCardDarkPurple,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedStat != null) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (selectedStat.isToday) "Today" else selectedStat.dayLabel,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${selectedStat.fullDate})",
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${selectedStat.trackCount} track${if (selectedStat.trackCount != 1) "s" else ""} played",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accentColor.copy(alpha = 0.18f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = formatListeningDuration(selectedStat.durationMs),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Tap any bar to inspect daily listening",
                            fontSize = 12.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
            }
        }

        // Bar Chart Visual Canvas & Columns
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            // Y-Axis Grid Guidelines
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gridColor = Color(0xFF38384A).copy(alpha = 0.35f)
                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                // Top (Max), Middle (50%), Baseline
                val yTop = 10f
                val yMid = size.height * 0.5f
                val yBottom = size.height - 30f

                drawLine(color = gridColor, start = Offset(0f, yTop), end = Offset(size.width, yTop), pathEffect = pathEffect, strokeWidth = 1.5f)
                drawLine(color = gridColor, start = Offset(0f, yMid), end = Offset(size.width, yMid), pathEffect = pathEffect, strokeWidth = 1.5f)
                drawLine(color = gridColor.copy(alpha = 0.6f), start = Offset(0f, yBottom), end = Offset(size.width, yBottom), strokeWidth = 2f)
            }

            // 7 Days Columns Row
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                dailyStats.forEachIndexed { index, stat ->
                    val isSelected = selectedIndex == index
                    val heightRatio = if (maxDurationMs > 0) {
                        (stat.durationMs.toFloat() / maxDurationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    val animatedHeightRatio by animateFloatAsState(
                        targetValue = heightRatio,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "barHeight_$index"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                selectedIndex = index
                                onDaySelected?.invoke(stat)
                            }
                            .padding(horizontal = 3.dp)
                    ) {
                        // Duration Tag on top of bar if active or non-zero
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                if (isSelected && stat.durationMs > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = accentColor,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    ) {
                                        Text(
                                            text = formatCompactDuration(stat.durationMs),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoOnPrimary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }

                                // Bar Shape
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(fraction = (animatedHeightRatio * 0.82f).coerceAtLeast(0.06f))
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                        .background(
                                            if (stat.durationMs > 0) {
                                                if (isSelected) {
                                                    Brush.verticalGradient(
                                                        colors = listOf(
                                                            accentColor,
                                                            BentoAccentPinkText
                                                        )
                                                    )
                                                } else {
                                                    Brush.verticalGradient(
                                                        colors = listOf(
                                                            accentColor.copy(alpha = 0.85f),
                                                            accentColor.copy(alpha = 0.4f)
                                                        )
                                                    )
                                                }
                                            } else {
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color(0xFF3A3A4C).copy(alpha = 0.5f),
                                                        Color(0xFF262635).copy(alpha = 0.3f)
                                                    )
                                                )
                                            }
                                        )
                                        .then(
                                            if (isSelected) {
                                                Modifier.border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                            } else Modifier
                                        )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Day Label (Mon, Tue, ..., Today)
                        Text(
                            text = stat.dayLabel,
                            fontSize = if (stat.isToday) 11.sp else 10.sp,
                            fontWeight = if (stat.isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else if (stat.isToday) accentColor else BentoTextSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )

                        // Date (e.g. 29)
                        Text(
                            text = stat.fullDate.substringAfter(' '),
                            fontSize = 9.sp,
                            color = if (isSelected) BentoTextPrimary else BentoTextSubtle,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

fun formatListeningDuration(ms: Long): String {
    if (ms <= 0L) return "0 mins"
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L

    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours} hr${if (hours > 1) "s" else ""}"
        mins > 0 -> "${mins} min${if (mins > 1) "s" else ""}"
        else -> "${secs} sec${if (secs != 1L) "s" else ""}"
    }
}

fun formatCompactDuration(ms: Long): String {
    if (ms <= 0L) return "0m"
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L

    return when {
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins}m"
    }
}
