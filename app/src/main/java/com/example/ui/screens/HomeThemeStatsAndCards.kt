package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Song
import com.example.ui.components.AlbumArtView
import com.example.ui.theme.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

private val themeStatsCache = ConcurrentHashMap<StatsPeriod, Triple<Long, Int, Int>>()

@Composable
private fun rememberPeriodMetrics(
    period: StatsPeriod,
    getDurationPlayedBetween: (Long, Long) -> Flow<Long>,
    getTrackCountBetween: (Long, Long) -> Flow<Int>,
    getUniqueTrackCountBetween: (Long, Long) -> Flow<Int>
): Triple<String, Int, Int> {
    val range = remember(period) {
        val todayMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        when (period) {
            StatsPeriod.TODAY -> Pair(todayMidnight, Long.MAX_VALUE)
            StatsPeriod.YESTERDAY -> {
                val yest = Calendar.getInstance().apply {
                    timeInMillis = todayMidnight
                    add(Calendar.DAY_OF_YEAR, -1)
                }.timeInMillis
                Pair(yest, todayMidnight)
            }
            StatsPeriod.WEEK -> {
                val weekAgo = Calendar.getInstance().apply {
                    timeInMillis = todayMidnight
                    add(Calendar.DAY_OF_YEAR, -6)
                }.timeInMillis
                Pair(weekAgo, Long.MAX_VALUE)
            }
            StatsPeriod.MONTH -> {
                val monthAgo = Calendar.getInstance().apply {
                    timeInMillis = todayMidnight
                    add(Calendar.DAY_OF_YEAR, -29)
                }.timeInMillis
                Pair(monthAgo, Long.MAX_VALUE)
            }
            StatsPeriod.ALL_TIME -> Pair(0L, Long.MAX_VALUE)
        }
    }

    val cached = remember(period) { themeStatsCache[period] }
    val durationFlow = remember(range.first, range.second) { getDurationPlayedBetween(range.first, range.second) }
    val trackFlow = remember(range.first, range.second) { getTrackCountBetween(range.first, range.second) }
    val uniqueFlow = remember(range.first, range.second) { getUniqueTrackCountBetween(range.first, range.second) }

    val durationMs by durationFlow.collectAsStateWithLifecycle(initialValue = cached?.first ?: -1L)
    val trackCount by trackFlow.collectAsStateWithLifecycle(initialValue = cached?.second ?: -1)
    val uniqueCount by uniqueFlow.collectAsStateWithLifecycle(initialValue = cached?.third ?: -1)

    LaunchedEffect(period, durationMs, trackCount, uniqueCount) {
        if (durationMs >= 0L && trackCount >= 0 && uniqueCount >= 0) {
            themeStatsCache[period] = Triple(durationMs, trackCount, uniqueCount)
        }
    }

    val formattedTime = remember(durationMs) {
        if (durationMs < 0L) return@remember "0m"
        val totalMins = durationMs / 1000 / 60
        val hrs = totalMins / 60
        val mins = totalMins % 60
        if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
    }

    return Triple(formattedTime, trackCount.coerceAtLeast(0), uniqueCount.coerceAtLeast(0))
}

/**
 * Bento Mosaic Continue Listening: Asymmetric 2-tile split (Info/Scrub Tile + Tactile Play/Pause Tile).
 */
@Composable
fun BentoMosaicContinueRow(
    song: Song,
    isCurrentActive: Boolean,
    isPlaying: Boolean,
    durationMs: Long,
    playbackPositionFlow: StateFlow<Long>?,
    onOpenNowPlaying: () -> Unit,
    onPlayPauseClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Left Major Bento Tile (Track Info + Real-time Progress)
        Surface(
            onClick = onOpenNowPlaying,
            shape = RoundedCornerShape(20.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
            modifier = Modifier
                .weight(1.48f)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AlbumArtView(
                        song = song,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(13.dp)),
                        cornerRadius = 13
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (isCurrentActive && isPlaying) "NOW SPINNING" else "RESUME TRACK",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoPrimary,
                            letterSpacing = 0.7.sp,
                            maxLines = 1
                        )
                        Text(
                            text = song.displayTitle,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.displayArtist,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (durationMs > 0L) {
                    com.example.ui.components.RealtimeTrackProgressBar(
                        playbackPositionFlow = playbackPositionFlow,
                        isActive = isCurrentActive,
                        durationMs = durationMs,
                        showTimestamps = isCurrentActive,
                        height = 3.5.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Right Companion Action Bento Tile (Tactile Play / Pause Switch)
        Surface(
            onClick = onPlayPauseClick,
            shape = RoundedCornerShape(20.dp),
            color = if (isCurrentActive && isPlaying) BentoPrimary else BentoCardSurface,
            border = BorderStroke(
                width = 1.dp,
                color = if (isCurrentActive && isPlaying) BentoPrimary else BentoPrimary.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .weight(0.52f)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (!(isCurrentActive && isPlaying)) {
                            Modifier.background(
                                Brush.verticalGradient(
                                    listOf(
                                        BentoPrimary.copy(alpha = 0.16f),
                                        BentoSecondary.copy(alpha = 0.08f)
                                    )
                                )
                            )
                        } else Modifier
                    )
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = if (isCurrentActive && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isCurrentActive && isPlaying) "Pause" else "Play",
                        tint = if (isCurrentActive && isPlaying) BentoOnPrimary else BentoPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = if (isCurrentActive && isPlaying) "PAUSE" else "PLAY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.9.sp,
                        color = if (isCurrentActive && isPlaying) BentoOnPrimary else BentoTextPrimary
                    )
                }
            }
        }
    }
}

/**
 * Bento Mosaic Listening Statistics: 2+3 Modular Dashboard Grid (distinct from horizontal carousels).
 */
@Composable
fun BentoMosaicStatsGrid(
    getDurationPlayedBetween: (Long, Long) -> Flow<Long>,
    getTrackCountBetween: (Long, Long) -> Flow<Int>,
    getUniqueTrackCountBetween: (Long, Long) -> Flow<Int>,
    onClick: () -> Unit
) {
    val today = rememberPeriodMetrics(StatsPeriod.TODAY, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)
    val yesterday = rememberPeriodMetrics(StatsPeriod.YESTERDAY, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)
    val week = rememberPeriodMetrics(StatsPeriod.WEEK, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)
    val month = rememberPeriodMetrics(StatsPeriod.MONTH, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)
    val allTime = rememberPeriodMetrics(StatsPeriod.ALL_TIME, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Row 1: Two Featured Asymmetric Bento Tiles (Today + This Week)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(20.dp),
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.40f)),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(BentoPrimary.copy(alpha = 0.16f), Color.Transparent)
                            )
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TODAY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoPrimary,
                            letterSpacing = 0.9.sp
                        )
                        Icon(
                            imageVector = Icons.Default.Today,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = today.first,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "${today.second} plays • ${today.third} tracks",
                        fontSize = 11.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(20.dp),
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.50f)),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(BentoSecondary.copy(alpha = 0.16f), Color.Transparent)
                            )
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "THIS WEEK",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoTextSecondary,
                            letterSpacing = 0.9.sp
                        )
                        Icon(
                            imageVector = Icons.Default.Insights,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = week.first,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "${week.second} plays • ${week.third} tracks",
                        fontSize = 11.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Row 2: Three Compact Metric Bento Tiles (Yesterday, Month, All Time)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple("YESTERDAY", yesterday.first, "${yesterday.second} plays"),
                Triple("30 DAYS", month.first, "${month.second} plays"),
                Triple("ALL TIME", allTime.first, "${allTime.second} plays")
            ).forEach { (label, value, sub) ->
                Surface(
                    onClick = onClick,
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.42f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoTextSecondary,
                            letterSpacing = 0.6.sp,
                            maxLines = 1
                        )
                        Text(
                            text = value,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoTextPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = sub,
                            fontSize = 10.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Studio Deck Precision Console Key: Architectural horizontal pad with left accent bar and monospace badge.
 */
@Composable
fun StudioConsoleKeyTile(
    label: String,
    badge: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = badge,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextSecondary,
                    maxLines = 1
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Studio Deck Unified Telemetry Console: Hairline-divided multi-channel listening readout panel.
 */
@Composable
fun StudioDeckTelemetryConsole(
    getDurationPlayedBetween: (Long, Long) -> Flow<Long>,
    getTrackCountBetween: (Long, Long) -> Flow<Int>,
    getUniqueTrackCountBetween: (Long, Long) -> Flow<Int>,
    onClick: () -> Unit
) {
    val today = rememberPeriodMetrics(StatsPeriod.TODAY, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)
    val yesterday = rememberPeriodMetrics(StatsPeriod.YESTERDAY, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)
    val week = rememberPeriodMetrics(StatsPeriod.WEEK, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)
    val month = rememberPeriodMetrics(StatsPeriod.MONTH, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)
    val allTime = rememberPeriodMetrics(StatsPeriod.ALL_TIME, getDurationPlayedBetween, getTrackCountBetween, getUniqueTrackCountBetween)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.50f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top 3-Channel Meter Readout Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val primaryCols = listOf(
                    Triple("TODAY", today.first, "${today.second} TRK"),
                    Triple("YEST", yesterday.first, "${yesterday.second} TRK"),
                    Triple("7 DAYS", week.first, "${week.second} TRK")
                )
                primaryCols.forEachIndexed { idx, (code, duration, count) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = code,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (idx == 0) BentoPrimary else BentoTextSecondary,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = duration,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoTextPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = count,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = BentoTextSecondary,
                            maxLines = 1
                        )
                    }
                    if (idx < primaryCols.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(BentoBorder.copy(alpha = 0.45f))
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = BentoBorder.copy(alpha = 0.40f))

            // Footer Cumulative Telemetry Strip (30 Days | All Time)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BentoPrimary.copy(alpha = 0.07f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "30D",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = BentoPrimary
                    )
                    Text(
                        text = month.first,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "(${month.second})",
                        fontSize = 11.sp,
                        color = BentoTextSecondary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "TOTAL",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = BentoPrimary
                    )
                    Text(
                        text = allTime.first,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "(${allTime.second})",
                        fontSize = 11.sp,
                        color = BentoTextSecondary
                    )
                }
            }
        }
    }
}
