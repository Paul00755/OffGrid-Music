package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DailyListeningStat
import com.example.ui.theme.*
import kotlin.math.ceil

private data class ChartBucketStat(
    val primaryLabel: String,
    val secondaryLabel: String,
    val rangeTitle: String,
    val durationMs: Long,
    val trackCount: Int,
    val isCurrent: Boolean,
    val representativeStat: DailyListeningStat
)

@Composable
fun DailyListeningBarChart(
    dailyStats: List<DailyListeningStat>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
    showSummaryHeader: Boolean = true,
    accentColor: Color = BentoPrimary,
    onDaySelected: ((DailyListeningStat) -> Unit)? = null
) {
    // Group large ranges (30d, 90d, 365d) into at most 10 readable buckets so labels never stack vertically
    val buckets = remember(dailyStats) {
        if (dailyStats.size <= 10) {
            dailyStats.map { stat ->
                ChartBucketStat(
                    primaryLabel = stat.dayLabel,
                    secondaryLabel = stat.fullDate.substringAfter(' '),
                    rangeTitle = if (stat.isToday) "Today (${stat.fullDate})" else "${stat.dayLabel} (${stat.fullDate})",
                    durationMs = stat.durationMs,
                    trackCount = stat.trackCount,
                    isCurrent = stat.isToday,
                    representativeStat = stat
                )
            }
        } else {
            val targetBuckets = if (dailyStats.size <= 35) 10 else 10
            val chunkSize = ceil(dailyStats.size / targetBuckets.toDouble()).toInt().coerceAtLeast(1)
            dailyStats.chunked(chunkSize).map { chunk ->
                val first = chunk.first()
                val last = chunk.last()
                val totalDuration = chunk.sumOf { it.durationMs }
                val totalTracks = chunk.sumOf { it.trackCount }
                val containsToday = chunk.any { it.isToday }
                val firstMonth = first.fullDate.substringBefore(' ')
                val firstDayNum = first.fullDate.substringAfter(' ')
                val lastDayNum = last.fullDate.substringAfter(' ')
                val primary = if (dailyStats.size > 120) {
                    last.fullDate.substringBefore(' ')
                } else {
                    firstMonth
                }
                val secondary = if (first.dayStartMs == last.dayStartMs) {
                    firstDayNum
                } else if (dailyStats.size > 120) {
                    "$firstDayNum–$lastDayNum"
                } else {
                    "$firstDayNum-$lastDayNum"
                }
                val title = if (first.dayStartMs == last.dayStartMs) {
                    "${first.dayLabel} (${first.fullDate})"
                } else {
                    "${first.fullDate} – ${last.fullDate}"
                }
                ChartBucketStat(
                    primaryLabel = primary,
                    secondaryLabel = secondary,
                    rangeTitle = title,
                    durationMs = totalDuration,
                    trackCount = totalTracks,
                    isCurrent = containsToday,
                    representativeStat = last.copy(
                        durationMs = totalDuration,
                        trackCount = totalTracks
                    )
                )
            }
        }
    }

    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(buckets) {
        if (buckets.isNotEmpty()) {
            val todayIdx = buckets.indexOfFirst { it.isCurrent }
            selectedIndex = if (todayIdx != -1) todayIdx else buckets.lastIndex
        }
    }

    val selectedBucket = selectedIndex?.let { buckets.getOrNull(it) }

    val maxDurationMs = remember(buckets) {
        val max = buckets.maxOfOrNull { it.durationMs } ?: 0L
        maxOf(max, 15 * 60 * 1000L)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showSummaryHeader) {
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
                    if (selectedBucket != null) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedBucket.rangeTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${selectedBucket.trackCount} track${if (selectedBucket.trackCount != 1) "s" else ""} played",
                                fontSize = 12.sp,
                                color = BentoTextSecondary,
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

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
                                    text = formatListeningDuration(selectedBucket.durationMs),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Tap any bar to inspect listening activity",
                            fontSize = 12.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gridColor = Color(0xFF38384A).copy(alpha = 0.35f)
                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                val yTop = 10f
                val yMid = size.height * 0.5f
                val yBottom = size.height - 34f

                drawLine(color = gridColor, start = Offset(0f, yTop), end = Offset(size.width, yTop), pathEffect = pathEffect, strokeWidth = 1.5f)
                drawLine(color = gridColor, start = Offset(0f, yMid), end = Offset(size.width, yMid), pathEffect = pathEffect, strokeWidth = 1.5f)
                drawLine(color = gridColor.copy(alpha = 0.6f), start = Offset(0f, yBottom), end = Offset(size.width, yBottom), strokeWidth = 2f)
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                buckets.forEachIndexed { index, bucket ->
                    val isSelected = selectedIndex == index
                    val heightRatio = if (maxDurationMs > 0) {
                        (bucket.durationMs.toFloat() / maxDurationMs.toFloat()).coerceIn(0f, 1f)
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
                                onDaySelected?.invoke(bucket.representativeStat)
                            }
                            .padding(horizontal = if (buckets.size > 7) 2.dp else 3.dp)
                    ) {
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
                                if (isSelected && bucket.durationMs > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = accentColor,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    ) {
                                        Text(
                                            text = formatCompactDuration(bucket.durationMs),
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoOnPrimary,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(if (buckets.size > 7) 0.78f else 0.88f)
                                        .fillMaxHeight(fraction = (animatedHeightRatio * 0.82f).coerceAtLeast(0.06f))
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                        .background(
                                            if (bucket.durationMs > 0) {
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

                        Text(
                            text = bucket.primaryLabel,
                            fontSize = if (buckets.size > 7) 9.sp else 10.sp,
                            fontWeight = if (bucket.isCurrent || isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else if (bucket.isCurrent) accentColor else BentoTextSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )

                        Text(
                            text = bucket.secondaryLabel,
                            fontSize = 8.sp,
                            color = if (isSelected) BentoTextPrimary else BentoTextSubtle,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
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
