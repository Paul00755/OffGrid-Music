package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.service.PlaybackState
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

enum class DetailedStatsPeriod(val title: String, val days: Int) {
    SEVEN_DAYS("Last 7 Days", 7),
    THIRTY_DAYS("Last 30 Days", 30),
    NINETY_DAYS("Last 3 Months", 90),
    ALL_TIME("All Time", 0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningStatsDetailScreen(
    playbackState: PlaybackState,
    getDailyStats: (Int) -> Flow<List<DailyListeningStat>>,
    getTimeOfDayStats: (Long) -> Flow<TimeOfDayDistribution>,
    getDurationPlayedBetween: (Long, Long) -> Flow<Long>,
    getTrackCountBetween: (Long, Long) -> Flow<Int>,
    getUniqueTrackCountBetween: (Long, Long) -> Flow<Int>,
    getMostPlayedSongsSince: (Long, Int) -> Flow<List<Song>>,
    getTopArtistsSince: (Long, Int) -> Flow<List<ArtistStat>>,
    getTopAlbumsSince: (Long, Int) -> Flow<List<AlbumStat>>,
    onSongClick: (Song) -> Unit,
    onPlayQueue: (Song, List<Song>) -> Unit,
    onBackClick: () -> Unit,
    onOpenRecentlyPlayed: () -> Unit,
    loadWrappedData: (suspend (Long, Long) -> WrappedData)? = null,
    modifier: Modifier = Modifier
) {
    var selectedPeriod by remember { mutableStateOf(DetailedStatsPeriod.SEVEN_DAYS) }
    var currentStoryRange by remember { mutableStateOf(StoryTimeRange.LAST_7_DAYS) }
    var showWrappedDialog by remember { mutableStateOf(false) }
    var showSharePeriodDialog by remember { mutableStateOf(false) }
    var showShareStatsCardDialog by remember { mutableStateOf(false) }
    var screenEntered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(40)
        screenEntered = true
    }

    // Calculate start timestamp for selected filter using strict midnight boundaries
    val filterStartTimeMs = remember(selectedPeriod) {
        if (selectedPeriod == DetailedStatsPeriod.ALL_TIME) {
            0L
        } else {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -(selectedPeriod.days - 1))
            }
            cal.timeInMillis
        }
    }

    // Lifetime Flows
    val lifetimeDurationFlow = remember { getDurationPlayedBetween(0L, Long.MAX_VALUE) }
    val lifetimeDurationMs by lifetimeDurationFlow.collectAsState(initial = 0L)

    val lifetimeTrackCountFlow = remember { getTrackCountBetween(0L, Long.MAX_VALUE) }
    val lifetimeTrackCount by lifetimeTrackCountFlow.collectAsState(initial = 0)

    val lifetimeUniqueCountFlow = remember { getUniqueTrackCountBetween(0L, Long.MAX_VALUE) }
    val lifetimeUniqueCount by lifetimeUniqueCountFlow.collectAsState(initial = 0)

    val lifetimeTopSongsFlow = remember { getMostPlayedSongsSince(0L, 5) }
    val lifetimeTopSongs by lifetimeTopSongsFlow.collectAsState(initial = emptyList())

    val daysForPeriod = remember(selectedPeriod) {
        when (selectedPeriod) {
            DetailedStatsPeriod.SEVEN_DAYS -> 7
            DetailedStatsPeriod.THIRTY_DAYS -> 30
            DetailedStatsPeriod.NINETY_DAYS -> 90
            DetailedStatsPeriod.ALL_TIME -> 365
        }
    }
    // Filtered period Flows
    val dailyStatsFlow = remember(daysForPeriod) { getDailyStats(daysForPeriod) }
    val dailyStats by dailyStatsFlow.collectAsState(initial = emptyList())

    val timeOfDayFlow = remember(filterStartTimeMs) { getTimeOfDayStats(filterStartTimeMs) }
    val timeOfDay by timeOfDayFlow.collectAsState(initial = TimeOfDayDistribution())

    val totalDurationFlow = remember(filterStartTimeMs) { getDurationPlayedBetween(filterStartTimeMs, Long.MAX_VALUE) }
    val totalDurationMs by totalDurationFlow.collectAsState(initial = 0L)

    val trackCountFlow = remember(filterStartTimeMs) { getTrackCountBetween(filterStartTimeMs, Long.MAX_VALUE) }
    val trackCount by trackCountFlow.collectAsState(initial = 0)

    val uniqueTrackCountFlow = remember(filterStartTimeMs) { getUniqueTrackCountBetween(filterStartTimeMs, Long.MAX_VALUE) }
    val uniqueTrackCount by uniqueTrackCountFlow.collectAsState(initial = 0)

    val topSongsFlow = remember(filterStartTimeMs) { getMostPlayedSongsSince(filterStartTimeMs, 20) }
    val topSongs by topSongsFlow.collectAsState(initial = emptyList())

    val mostPlayedLifetimeSong = lifetimeTopSongs.firstOrNull() ?: topSongs.firstOrNull()

    val topArtistsFlow = remember(filterStartTimeMs) { getTopArtistsSince(filterStartTimeMs, 10) }
    val topArtists by topArtistsFlow.collectAsState(initial = emptyList())

    val topAlbumsFlow = remember(filterStartTimeMs) { getTopAlbumsSince(filterStartTimeMs, 10) }
    val topAlbums by topAlbumsFlow.collectAsState(initial = emptyList())

    var periodLoadedWrappedData by remember { mutableStateOf<WrappedData?>(null) }
    LaunchedEffect(filterStartTimeMs, loadWrappedData) {
        if (loadWrappedData != null) {
            try {
                periodLoadedWrappedData = loadWrappedData(filterStartTimeMs, Long.MAX_VALUE)
            } catch (_: Exception) {}
        }
    }

    // Compute Wrapped Data
    val wrappedData = remember(periodLoadedWrappedData, totalDurationMs, trackCount, uniqueTrackCount, topSongs, topArtists, topAlbums, timeOfDay, dailyStats, mostPlayedLifetimeSong) {
        if (periodLoadedWrappedData != null) {
            return@remember periodLoadedWrappedData!!
        }
        val effectiveTopSong = topSongs.firstOrNull() ?: mostPlayedLifetimeSong
        val effectiveTopSongs = if (topSongs.isNotEmpty()) topSongs else (if (mostPlayedLifetimeSong != null) listOf(mostPlayedLifetimeSong) else emptyList())
        val totalMins = totalDurationMs / (1000L * 60L)
        val activeDays = dailyStats.count { it.durationMs > 0 }.coerceAtLeast(if (totalDurationMs > 0 || effectiveTopSong != null) 1 else 0)

        val dominantVibe = timeOfDay.dominantVibe
        val (archetype, desc) = when (dominantVibe) {
            "Night Owl" -> Pair("Night Owl Explorer", "Your peak hours are late at night with immersive, uninterrupted listening sessions.")
            "Early Bird" -> Pair("Dawn Melodist", "You start your mornings energized with music that sets the rhythm for your day.")
            "Daytime Groover" -> Pair("Midday Flow Master", "Music fuels your daytime focus, keeping your productivity high and spirits upbeat.")
            else -> Pair("Sonic Harmonizer", "You unwind in the evenings with rich soundscapes and eclectic favorites.")
        }

        val topSongPlays = if (effectiveTopSong != null && effectiveTopSong.playCount > 0) effectiveTopSong.playCount else (if (effectiveTopSong != null) 1 else 0)
        val topSongDuration = if (effectiveTopSong != null) topSongPlays * (if (effectiveTopSong.durationMs > 0) effectiveTopSong.durationMs else 180000L) else 0L
        val effectiveTrackCount = trackCount.coerceAtLeast(if (effectiveTopSong != null) 1 else 0)
        val effectiveUniqueTrackCount = uniqueTrackCount.coerceAtLeast(if (effectiveTopSong != null) 1 else 0)

        WrappedData(
            totalMinutes = totalMins,
            totalTracks = effectiveTrackCount,
            uniqueTracks = effectiveUniqueTrackCount,
            activeDaysCount = activeDays,
            topSong = effectiveTopSong,
            topSongPlayCount = topSongPlays,
            topSongDurationMs = topSongDuration,
            topSongs = effectiveTopSongs,
            topArtists = topArtists,
            topAlbums = topAlbums,
            timeOfDay = timeOfDay,
            listenerArchetype = archetype,
            archetypeDescription = desc
        )
    }

    if (showWrappedDialog) {
        WrappedStoryDialog(
            wrappedData = wrappedData,
            timePeriodTitle = currentStoryRange.displayLabel,
            initialRange = currentStoryRange,
            loadWrappedData = loadWrappedData,
            onDismiss = { showWrappedDialog = false },
            onPlaySong = { onSongClick(it) },
            onPlayTopQueue = { songs ->
                if (songs.isNotEmpty()) {
                    onPlayQueue(songs.first(), songs)
                }
            }
        )
    }

    if (showSharePeriodDialog) {
        SharePeriodSelectionDialog(
            selectedRange = currentStoryRange,
            onDismiss = { showSharePeriodDialog = false },
            onRangeSelected = { range ->
                currentStoryRange = range
                showSharePeriodDialog = false
                showShareStatsCardDialog = true
            }
        )
    }

    if (showShareStatsCardDialog) {
        ShareStatsCardDialog(
            wrappedData = wrappedData,
            timePeriodTitle = currentStoryRange.displayLabel,
            initialRange = currentStoryRange,
            initialPeriod = currentStoryRange.period,
            loadWrappedData = loadWrappedData,
            onDismiss = { showShareStatsCardDialog = false }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BentoBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Listening Insights",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Real-time analytics & listening trends",
                            fontSize = 12.sp,
                            color = BentoTextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = BentoTextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSharePeriodDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share My Stats Card",
                            tint = BentoPrimary
                        )
                    }
                    IconButton(onClick = { showWrappedDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "OffGrid Wrapped",
                            tint = BentoAccentPinkText
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BentoBackground)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp)
        ) {
            // Executive Wrapped & Lifetime Summary Card
            item {
                AnimatedStatsSection(visible = screenEntered, delayMs = 0) {
                    WrappedSummaryShowcaseCard(
                        lifetimePlaytimeMs = lifetimeDurationMs,
                        lifetimeTracksCount = lifetimeTrackCount,
                        lifetimeUniqueCount = lifetimeUniqueCount,
                        mostPlayedSong = mostPlayedLifetimeSong,
                        listenerArchetype = wrappedData.listenerArchetype,
                        isPlaying = playbackState.isPlaying,
                        currentSongId = playbackState.currentSong?.id,
                        onPlaySong = onSongClick,
                        onLaunchWrapped = { showWrappedDialog = true },
                        onShareStats = { showSharePeriodDialog = true }
                    )
                }
            }

            // Time Period Filter Chips
            item {
                AnimatedStatsSection(visible = screenEntered, delayMs = 70) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DetailedStatsPeriod.values().forEach { period ->
                            val isSelected = selectedPeriod == period
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedPeriod = period
                                    currentStoryRange = when (period) {
                                        DetailedStatsPeriod.SEVEN_DAYS -> StoryTimeRange.LAST_7_DAYS
                                        DetailedStatsPeriod.THIRTY_DAYS -> StoryTimeRange.THIS_MONTH
                                        DetailedStatsPeriod.NINETY_DAYS -> StoryTimeRange.forPeriod(StoryPeriod.LAST_MONTH)
                                        DetailedStatsPeriod.ALL_TIME -> StoryTimeRange.ALL_TIME
                                    }
                                },
                                label = {
                                    Text(
                                        text = period.title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BentoPrimary,
                                    selectedLabelColor = BentoOnPrimary,
                                    containerColor = BentoCardSurface,
                                    labelColor = BentoTextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = if (isSelected) BentoPrimary else BentoCardBorder
                                )
                            )
                        }
                    }
                }
            }

            // 7-Day Visual Summary Bar Chart
            item {
                AnimatedStatsSection(visible = screenEntered, delayMs = 140) {
                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BentoCardSurface
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.BarChart,
                                        contentDescription = null,
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "7-Day Listening Duration",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                }

                                val weeklyTotalMs = remember(dailyStats) { dailyStats.sumOf { it.durationMs } }
                                Text(
                                    text = "Total: ${formatListeningDuration(weeklyTotalMs)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            DailyListeningBarChart(
                                dailyStats = dailyStats,
                                chartHeight = 180.dp,
                                showSummaryHeader = true,
                                accentColor = BentoPrimary
                            )
                        }
                    }
                }
            }

            // Key Metrics Grid
            item {
                AnimatedStatsSection(visible = screenEntered, delayMs = 210) {
                    Column {
                        Text(
                            text = "Key Metrics (${selectedPeriod.title})",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Total Time Tile
                            BentoCard(
                                modifier = Modifier.weight(1f),
                                backgroundColor = BentoCardSurface
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.AccessTime,
                                        contentDescription = null,
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = formatListeningDuration(totalDurationMs),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Total Listened",
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }

                            // Total Tracks Tile
                            BentoCard(
                                modifier = Modifier.weight(1f),
                                backgroundColor = BentoCardSurface
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = BentoAccentPinkText,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "$trackCount plays",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$uniqueTrackCount unique tracks",
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Daily Average Tile
                            val daysDivider = if (selectedPeriod.days > 0) selectedPeriod.days else 30
                            val dailyAvgMs = if (daysDivider > 0) totalDurationMs / daysDivider else 0L

                            BentoCard(
                                modifier = Modifier.weight(1f),
                                backgroundColor = BentoCardSurface
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = Color(0xFF64B5F6),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = formatListeningDuration(dailyAvgMs),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Daily Average",
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }

                            // Dominant Vibe Tile
                            BentoCard(
                                modifier = Modifier.weight(1f),
                                backgroundColor = BentoCardSurface
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.NightsStay,
                                        contentDescription = null,
                                        tint = Color(0xFFBA68C8),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = timeOfDay.dominantVibe,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Listening Vibe",
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Listening Clock / Time of Day Breakdown
            item {
                AnimatedStatsSection(visible = screenEntered, delayMs = 280) {
                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BentoCardSurface
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Time of Day Distribution",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            val totalT = timeOfDay.totalMs.coerceAtLeast(1L)
                            val mPct = ((timeOfDay.morningDurationMs.toFloat() / totalT) * 100).toInt()
                            val aPct = ((timeOfDay.afternoonDurationMs.toFloat() / totalT) * 100).toInt()
                            val ePct = ((timeOfDay.eveningDurationMs.toFloat() / totalT) * 100).toInt()
                            val nPct = ((timeOfDay.nightDurationMs.toFloat() / totalT) * 100).toInt()

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                DetailedTimeSlotRow(
                                    title = "Morning (05:00 - 12:00)",
                                    pct = mPct,
                                    durationMs = timeOfDay.morningDurationMs,
                                    color = Color(0xFFFFB74D)
                                )
                                DetailedTimeSlotRow(
                                    title = "Afternoon (12:00 - 17:00)",
                                    pct = aPct,
                                    durationMs = timeOfDay.afternoonDurationMs,
                                    color = Color(0xFF4FC3F7)
                                )
                                DetailedTimeSlotRow(
                                    title = "Evening (17:00 - 22:00)",
                                    pct = ePct,
                                    durationMs = timeOfDay.eveningDurationMs,
                                    color = Color(0xFFBA68C8)
                                )
                                DetailedTimeSlotRow(
                                    title = "Night Owl (22:00 - 05:00)",
                                    pct = nPct,
                                    durationMs = timeOfDay.nightDurationMs,
                                    color = Color(0xFF7986CB)
                                )
                            }
                        }
                    }
                }
            }

            // Top Songs Section
            item {
                AnimatedStatsSection(visible = screenEntered, delayMs = 350) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Most Played Tracks",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "${topSongs.size} tracks",
                            fontSize = 12.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
            }

            if (topSongs.isNotEmpty()) {
                itemsIndexed(topSongs, key = { _, s -> "top_${s.id}" }) { index, song ->
                    AnimatedStatsSection(
                        visible = screenEntered,
                        delayMs = (370 + index * 30).coerceAtMost(600)
                    ) {
                        BentoCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlayQueue(song, topSongs) },
                            backgroundColor = BentoCardSurface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "#${index + 1}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (index < 3) BentoPrimary else BentoTextSecondary,
                                    modifier = Modifier.width(32.dp)
                                )
                                AlbumArtView(
                                    song = song,
                                    modifier = Modifier.size(46.dp),
                                    cornerRadius = 8
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.displayTitle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${song.displayArtist} • ${song.displayAlbum}",
                                        fontSize = 12.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (song.playCount > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = BentoPrimary.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "${song.playCount} plays",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Text(
                        text = "No songs played during this period yet.",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }

            // Top Artists
            if (topArtists.isNotEmpty()) {
                item {
                    AnimatedStatsSection(visible = screenEntered, delayMs = 450) {
                        Column {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Top Artists",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            BentoCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = BentoCardSurface
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    topArtists.take(6).forEachIndexed { idx, artist ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 5.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${idx + 1}.",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BentoPrimary,
                                                    modifier = Modifier.width(24.dp)
                                                )
                                                Text(
                                                    text = artist.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = BentoTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = "${artist.playCount} plays (${formatListeningDuration(artist.totalDurationMs)})",
                                                fontSize = 11.sp,
                                                color = BentoTextSecondary
                                            )
                                        }
                                        if (idx < topArtists.take(6).lastIndex) {
                                            HorizontalDivider(
                                                color = BentoCardBorder.copy(alpha = 0.4f),
                                                modifier = Modifier.padding(vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Jump to Recently Played Button
            item {
                AnimatedStatsSection(visible = screenEntered, delayMs = 500) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onOpenRecentlyPlayed,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "View All Chronological History",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedStatsSection(
    visible: Boolean,
    delayMs: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = 380,
                delayMillis = delayMs,
                easing = FastOutSlowInEasing
            )
        ) + slideInVertically(
            initialOffsetY = { 35 },
            animationSpec = tween(
                durationMillis = 420,
                delayMillis = delayMs,
                easing = FastOutSlowInEasing
            )
        ),
        exit = fadeOut(animationSpec = tween(150)),
        modifier = modifier
    ) {
        content()
    }
}

@Composable
private fun DetailedTimeSlotRow(
    title: String,
    pct: Int,
    durationMs: Long,
    color: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = BentoTextPrimary,
                fontWeight = FontWeight.Medium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatListeningDuration(durationMs),
                    fontSize = 11.sp,
                    color = BentoTextSecondary,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = "$pct%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (pct / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = color,
            trackColor = BentoCardBorder.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun WrappedSummaryShowcaseCard(
    lifetimePlaytimeMs: Long,
    lifetimeTracksCount: Int,
    lifetimeUniqueCount: Int,
    mostPlayedSong: Song?,
    listenerArchetype: String,
    isPlaying: Boolean,
    currentSongId: Long?,
    onPlaySong: (Song) -> Unit,
    onLaunchWrapped: () -> Unit,
    onShareStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF140F22),
        border = BorderStroke(
            width = 1.5.dp,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    BentoAccentPinkText.copy(alpha = 0.85f),
                    BentoPrimary.copy(alpha = 0.75f),
                    Color(0xFFAB47BC).copy(alpha = 0.6f)
                )
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(24.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF2E1340),
                            Color(0xFF1B142F),
                            Color(0xFF100D1E)
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Row: Capsule Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = BentoAccentPinkText.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, BentoAccentPinkText.copy(alpha = 0.4f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = BentoAccentPinkText,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "LIFETIME WRAPPED RECAP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                color = BentoAccentPinkText
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = listenerArchetype,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.95f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Lifetime Playtime Hero Section
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "TOTAL LIFETIME PLAYTIME",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = formatListeningDuration(lifetimePlaytimeMs),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                letterSpacing = (-0.6).sp
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BentoPrimary.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "$lifetimeTracksCount total plays",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                            ) {
                                Text(
                                    text = "$lifetimeUniqueCount unique songs",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                // Most Played Song Hero Banner
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF1E1736).copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, BentoAccentPinkText.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.WorkspacePremium,
                                    contentDescription = null,
                                    tint = BentoAccentPinkText,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "MOST PLAYED ALL-TIME ANTHEM",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.6.sp,
                                    color = BentoAccentPinkText
                                )
                            }

                            if (mostPlayedSong != null && mostPlayedSong.playCount > 0) {
                                Text(
                                    text = "${mostPlayedSong.playCount} plays",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (mostPlayedSong != null) {
                            val isThisSongPlaying = isPlaying && currentSongId == mostPlayedSong.id

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(modifier = Modifier.size(52.dp)) {
                                        AlbumArtView(
                                            song = mostPlayedSong,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(12.dp)),
                                            cornerRadius = 12
                                        )
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFFFFD54F),
                                            modifier = Modifier
                                                .size(20.dp)
                                                .align(Alignment.TopStart)
                                                .offset(x = (-4).dp, y = (-4).dp)
                                                .shadow(4.dp, CircleShape)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "1",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = Color.Black
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column {
                                        Text(
                                            text = mostPlayedSong.displayTitle,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${mostPlayedSong.displayArtist} • ${formatListeningDuration(mostPlayedSong.playCount * mostPlayedSong.durationMs)}",
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Surface(
                                    shape = CircleShape,
                                    color = if (isThisSongPlaying) BentoAccentPinkText else BentoPrimary,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .shadow(6.dp, CircleShape)
                                        .clickable { onPlaySong(mostPlayedSong) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isThisSongPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (isThisSongPlaying) "Pause Anthem" else "Play Anthem",
                                            tint = if (isThisSongPlaying) Color.Black else BentoOnPrimary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Play tracks to crown your all-time favorite musical anthem!",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.65f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                // Interactive CTA Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onLaunchWrapped,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoAccentPinkText,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Launch Story Recap",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    OutlinedButton(
                        onClick = onShareStats,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.75f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BentoPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share Card",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
