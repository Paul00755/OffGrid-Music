package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.service.PlaybackState
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val context = LocalContext.current
    var selectedPeriod by remember { mutableStateOf(DetailedStatsPeriod.SEVEN_DAYS) }
    var currentStoryRange by remember { mutableStateOf(StoryTimeRange.LAST_7_DAYS) }
    var showWrappedDialog by remember { mutableStateOf(false) }
    var showStoryPeriodDialog by remember { mutableStateOf(false) }
    var showSharePeriodDialog by remember { mutableStateOf(false) }
    var showShareStatsCardDialog by remember { mutableStateOf(false) }
    var screenEntered by remember { mutableStateOf(true) }

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

    // 365-day Flow for the Listening Activity Heatmap
    val heatmapStatsFlow = remember { getDailyStats(365) }
    val heatmapStats by heatmapStatsFlow.collectAsState(initial = emptyList())

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

    LaunchedEffect(topSongs) {
        if (topSongs.isNotEmpty()) {
            com.example.ui.components.ArtworkResolutionCache.preloadWindow(
                context = context,
                songs = topSongs,
                firstVisibleIndex = 0,
                windowAhead = 20,
                windowBehind = 0
            )
        }
    }

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

    // Compute real-time Streak stats from 365-day heatmapStats + repository WrappedData
    val computedStreaks = remember(heatmapStats, dailyStats, periodLoadedWrappedData, totalDurationMs) {
        val sourceDays = if (heatmapStats.isNotEmpty()) heatmapStats else dailyStats
        var longest = 0
        var run = 0
        for (stat in sourceDays) {
            if (stat.durationMs > 0L) {
                run += 1
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }

        var current = 0
        if (sourceDays.isNotEmpty()) {
            val lastIdx = sourceDays.lastIndex
            val startIdx = when {
                sourceDays[lastIdx].durationMs > 0L -> lastIdx
                lastIdx >= 1 && sourceDays[lastIdx - 1].durationMs > 0L -> lastIdx - 1
                else -> -1
            }
            if (startIdx >= 0) {
                var i = startIdx
                while (i >= 0 && sourceDays[i].durationMs > 0L) {
                    current += 1
                    i -= 1
                }
            }
        }

        val finalCurrent = maxOf(current, periodLoadedWrappedData?.currentStreakDays ?: 0)
        val finalLongest = maxOf(longest, periodLoadedWrappedData?.longestStreakDays ?: 0, finalCurrent)
        Pair(finalCurrent, finalLongest)
    }
    val currentStreakDays = computedStreaks.first
    val longestStreakDays = computedStreaks.second

    // Compute Wrapped Data
    val wrappedData = remember(periodLoadedWrappedData, totalDurationMs, trackCount, uniqueTrackCount, topSongs, topArtists, topAlbums, timeOfDay, dailyStats, mostPlayedLifetimeSong, currentStreakDays, longestStreakDays) {
        if (periodLoadedWrappedData != null) {
            return@remember periodLoadedWrappedData!!.copy(
                currentStreakDays = maxOf(periodLoadedWrappedData!!.currentStreakDays, currentStreakDays),
                longestStreakDays = maxOf(periodLoadedWrappedData!!.longestStreakDays, longestStreakDays)
            )
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
            currentStreakDays = currentStreakDays,
            longestStreakDays = longestStreakDays,
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

    if (showStoryPeriodDialog) {
        SharePeriodSelectionDialog(
            selectedRange = currentStoryRange,
            title = "Launch Story Recap",
            subtitle = "Choose a date, range, or timeframe for your Story",
            onDismiss = { showStoryPeriodDialog = false },
            onRangeSelected = { range ->
                currentStoryRange = range
                showStoryPeriodDialog = false
                showWrappedDialog = true
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
        containerColor = BentoScreenBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
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
                    IconButton(onClick = { showStoryPeriodDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "OffGrid Wrapped",
                            tint = BentoAccentPinkText
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        LaunchedEffect(topSongs) {
            if (topSongs.isNotEmpty()) {
                com.example.ui.components.ArtworkResolutionCache.preloadWindow(
                    context = context,
                    songs = topSongs,
                    firstVisibleIndex = 0,
                    windowAhead = 25,
                    windowBehind = 0
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp)
        ) {
            // Executive Wrapped & Lifetime Summary Card
            item(key = "stats_wrapped_summary", contentType = "stats_wrapped_summary") {
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
                        onLaunchWrapped = { showStoryPeriodDialog = true },
                        onShareStats = { showSharePeriodDialog = true }
                    )
                }
            }

            // Dedicated Listening Streak Showcase Card (Current Streak & Highest Streak)
            item(key = "stats_listening_streak", contentType = "stats_listening_streak") {
                AnimatedStatsSection(visible = screenEntered, delayMs = 45) {
                    ListeningStreakShowcaseCard(
                        currentStreakDays = currentStreakDays,
                        longestStreakDays = longestStreakDays,
                        recentDays = (if (heatmapStats.isNotEmpty()) heatmapStats else dailyStats).takeLast(7)
                    )
                }
            }

            // Time Period Filter Chips
            item(key = "stats_period_chips", contentType = "stats_period_chips") {
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

            // Period Visual Summary Bar Chart
            item(key = "stats_bar_chart", contentType = "stats_bar_chart") {
                AnimatedStatsSection(visible = screenEntered, delayMs = 140) {
                    val chartTitle = remember(selectedPeriod) {
                        when (selectedPeriod) {
                            DetailedStatsPeriod.SEVEN_DAYS -> "7-Day Listening Duration"
                            DetailedStatsPeriod.THIRTY_DAYS -> "30-Day Listening Duration"
                            DetailedStatsPeriod.NINETY_DAYS -> "3-Month Listening Duration"
                            DetailedStatsPeriod.ALL_TIME -> "All-Time Listening Duration"
                        }
                    }
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
                                        text = chartTitle,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                }

                                val periodTotalMs = remember(dailyStats) { dailyStats.sumOf { it.durationMs } }
                                Text(
                                    text = "Total: ${formatListeningDuration(periodTotalMs)}",
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

            // Listening Activity Heatmap (GitHub-style contribution grid)
            item(key = "stats_heatmap", contentType = "stats_heatmap") {
                AnimatedStatsSection(visible = screenEntered, delayMs = 175) {
                    ListeningActivityHeatmapCard(
                        dailyStats = heatmapStats,
                        accentColor = BentoPrimary
                    )
                }
            }

            // Key Metrics Grid
            item(key = "stats_key_metrics", contentType = "stats_key_metrics") {
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
            item(key = "stats_time_of_day", contentType = "stats_time_of_day") {
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
            item(key = "stats_top_songs_header", contentType = "stats_section_header") {
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
                itemsIndexed(
                    items = topSongs,
                    key = { _, s -> "top_${s.id}" },
                    contentType = { _, _ -> "stats_top_song_item" }
                ) { index, song ->
                    val onTopSongClick = remember(song, topSongs, onPlayQueue) {
                        { onPlayQueue(song, topSongs) }
                    }
                    BentoCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onTopSongClick),
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
            } else {
                item(key = "stats_top_songs_empty", contentType = "stats_empty") {
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
                item(key = "stats_top_artists", contentType = "stats_top_artists") {
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
            item(key = "stats_history_button", contentType = "stats_history_button") {
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
    if (visible) {
        Box(modifier = modifier) {
            content()
        }
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
    val glassConfig = com.example.ui.theme.LocalGlassThemeConfig.current
    val cardBaseColor = if (glassConfig.glassEnabled) BentoCardSurface else Color(0xFF140F22)
    val cardGradientColors = if (glassConfig.gradientEnabled || glassConfig.glassEnabled) {
        val alphaTop = if (glassConfig.glassEnabled) 0.42f else 0.90f
        val alphaMid = if (glassConfig.glassEnabled) 0.28f else 0.92f
        val alphaBot = if (glassConfig.glassEnabled) 0.35f else 0.96f
        listOf(
            androidx.compose.ui.graphics.lerp(Color(0xFF181226), glassConfig.tintColor, 0.32f).copy(alpha = alphaTop),
            androidx.compose.ui.graphics.lerp(Color(0xFF120E1E), glassConfig.secondaryTintColor, 0.22f).copy(alpha = alphaMid),
            Color(0xFF0E0B18).copy(alpha = alphaBot)
        )
    } else {
        listOf(
            Color(0xFF2E1340),
            Color(0xFF1B142F),
            Color(0xFF100D1E)
        )
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardBaseColor,
        border = BorderStroke(
            width = 1.5.dp,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    BentoAccentPinkText.copy(alpha = 0.85f),
                    BentoPrimary.copy(alpha = 0.75f),
                    BentoPrimary.copy(alpha = 0.45f)
                )
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (glassConfig.glassEnabled) 0.dp else 16.dp, RoundedCornerShape(24.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = cardGradientColors
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

@Composable
private fun ListeningActivityHeatmapCard(
    dailyStats: List<DailyListeningStat>,
    accentColor: Color = BentoPrimary
) {
    // Build a 7-row (Sun..Sat) x N-week matrix aligned by day of week, covering up to 52 weeks
    val weeksData = remember(dailyStats) {
        if (dailyStats.isEmpty()) {
            emptyList<List<DailyListeningStat?>>()
        } else {
            val cal = Calendar.getInstance().apply {
                timeInMillis = dailyStats.first().dayStartMs
            }
            // Shift so Monday = 0, Tuesday = 1, ..., Sunday = 6 (matches Mon/Wed/Fri row labels)
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // Sun=1..Sat=7
            val leadingNulls = when (dayOfWeek) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                else -> 6
            }
            val padded = buildList<DailyListeningStat?> {
                repeat(leadingNulls) { add(null) }
                addAll(dailyStats)
            }
            padded.chunked(7).map { week ->
                if (week.size < 7) week + List(7 - week.size) { null } else week
            }
        }
    }

    var selectedDay by remember(dailyStats) {
        mutableStateOf(dailyStats.lastOrNull { it.isToday } ?: dailyStats.lastOrNull())
    }

    val maxDurationMs = remember(dailyStats) {
        val max = dailyStats.maxOfOrNull { it.durationMs } ?: 0L
        maxOf(max, 30 * 60 * 1000L) // minimum 30 min scale for 4 intensity tiers
    }

    val activeDaysCount = remember(dailyStats) {
        dailyStats.count { it.durationMs > 0L }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(weeksData.size) {
        if (weeksData.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

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
                        imageVector = Icons.Default.GridOn,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Listening Activity Heatmap",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                }

                Text(
                    text = "$activeDaysCount active day${if (activeDaysCount != 1) "s" else ""}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Selected Day Info Pill
            selectedDay?.let { day ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BentoCardDarkPurple,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (day.isToday) "Today • ${day.fullDate}" else "${day.dayLabel} • ${day.fullDate}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "${formatListeningDuration(day.durationMs)} (${day.trackCount} track${if (day.trackCount != 1) "s" else ""})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Day-of-week labels column (Mon, Wed, Fri) matching reference heatmap
                Column(
                    modifier = Modifier
                        .padding(top = 18.dp, end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val dayLabels = listOf("Mon", "", "Wed", "", "Fri", "", "")
                    dayLabels.forEach { label ->
                        Box(
                            modifier = Modifier.height(12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = label,
                                fontSize = 9.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Horizontally scrollable weeks grid with month headers on top and single-Canvas 7x53 cell matrix
                val weekHeaders = remember(weeksData) {
                    var lastShownMonth = ""
                    weeksData.map { week ->
                        val firstStatInWeek = week.firstOrNull { it != null }
                        val monthName = firstStatInWeek?.fullDate?.substringBefore(' ') ?: ""
                        val dayNum = firstStatInWeek?.fullDate?.substringAfter(' ')?.toIntOrNull() ?: 99
                        val showMonthHeader = monthName.isNotEmpty() && monthName != lastShownMonth && dayNum <= 14
                        if (showMonthHeader) {
                            lastShownMonth = monthName
                            monthName
                        } else {
                            ""
                        }
                    }
                }

                val totalGridWidthDp = remember(weeksData.size) {
                    val count = weeksData.size.coerceAtLeast(1)
                    (count * 12 + (count - 1) * 3).dp
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.height(15.dp)
                    ) {
                        weekHeaders.forEach { monthLabel ->
                            Box(
                                modifier = Modifier
                                    .height(15.dp)
                                    .width(12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (monthLabel.isNotEmpty()) {
                                    Text(
                                        text = monthLabel,
                                        fontSize = 9.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Visible
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    val emptyCellColor = Color(0xFF1E2230)
                    val tier1Color = accentColor.copy(alpha = 0.30f)
                    val tier2Color = accentColor.copy(alpha = 0.52f)
                    val tier3Color = accentColor.copy(alpha = 0.76f)
                    val selectedDayStartMs = selectedDay?.dayStartMs
                    val density = LocalDensity.current
                    val cellPx = with(density) { 12.dp.toPx() }
                    val gapPx = with(density) { 3.dp.toPx() }
                    val stepPx = cellPx + gapPx

                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .width(totalGridWidthDp)
                            .height(102.dp) // 7 * 12.dp + 6 * 3.dp
                            .pointerInput(weeksData, stepPx) {
                                detectTapGestures { tapOffset ->
                                    val col = (tapOffset.x / stepPx).toInt()
                                    val row = (tapOffset.y / stepPx).toInt()
                                    if (col in weeksData.indices && row in 0..6) {
                                        val tappedStat = weeksData[col].getOrNull(row)
                                        if (tappedStat != null) {
                                            selectedDay = tappedStat
                                        }
                                    }
                                }
                            }
                    ) {
                        val cellPx = 12.dp.toPx()
                        val gapPx = 3.dp.toPx()
                        val stepPx = cellPx + gapPx
                        val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        val cellSize = androidx.compose.ui.geometry.Size(cellPx, cellPx)
                        val borderStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())

                        for (col in weeksData.indices) {
                            val week = weeksData[col]
                            val x = col * stepPx
                            for (row in 0 until minOf(7, week.size)) {
                                val dayStat = week[row] ?: continue
                                val y = row * stepPx
                                val cellColor = if (dayStat.durationMs <= 0L) {
                                    emptyCellColor
                                } else {
                                    val ratio = (dayStat.durationMs.toFloat() / maxDurationMs.toFloat()).coerceIn(0f, 1f)
                                    when {
                                        ratio < 0.25f -> tier1Color
                                        ratio < 0.50f -> tier2Color
                                        ratio < 0.75f -> tier3Color
                                        else -> accentColor
                                    }
                                }
                                val topLeft = androidx.compose.ui.geometry.Offset(x, y)
                                drawRoundRect(
                                    color = cellColor,
                                    topLeft = topLeft,
                                    size = cellSize,
                                    cornerRadius = corner
                                )
                                if (selectedDayStartMs == dayStat.dayStartMs) {
                                    drawRoundRect(
                                        color = Color.White,
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = corner,
                                        style = borderStroke
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom-right "Less [][][][][] More" legend matching the reference image
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Less",
                    fontSize = 10.sp,
                    color = BentoTextSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                val legendColors = listOf(
                    Color(0xFF1E2230),
                    accentColor.copy(alpha = 0.30f),
                    accentColor.copy(alpha = 0.52f),
                    accentColor.copy(alpha = 0.76f),
                    accentColor
                )
                legendColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.5.dp)
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(color)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "More",
                    fontSize = 10.sp,
                    color = BentoTextSecondary
                )
            }
        }
    }
}

@Composable
fun ListeningStreakShowcaseCard(
    currentStreakDays: Int,
    longestStreakDays: Int,
    recentDays: List<DailyListeningStat>,
    modifier: Modifier = Modifier
) {
    val flameColor = Color(0xFFFF7043)
    val goldColor = Color(0xFFFFCA28)

    BentoCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = BentoCardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = flameColor.copy(alpha = 0.16f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = "Streak",
                                tint = flameColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Listening Streak",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = if (currentStreakDays > 0) "Daily music consistency" else "Play a track today to ignite your streak",
                            fontSize = 11.sp,
                            color = BentoTextSecondary
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (currentStreakDays > 0) flameColor.copy(alpha = 0.16f) else BentoCardDarkPurple.copy(alpha = 0.65f),
                    border = BorderStroke(
                        1.dp,
                        if (currentStreakDays > 0) flameColor.copy(alpha = 0.45f) else BentoCardBorder.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        text = if (currentStreakDays >= 7) "ON FIRE" else if (currentStreakDays > 0) "ACTIVE" else "READY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (currentStreakDays > 0) flameColor else BentoTextSecondary,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Two Main Bento Metric Tiles: Current Streak & Highest Streak
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Current Streak Block
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardDarkPurple.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, flameColor.copy(alpha = if (currentStreakDays > 0) 0.42f else 0.18f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = flameColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "CURRENT STREAK",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = flameColor,
                                letterSpacing = 0.6.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "$currentStreakDays",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = if (currentStreakDays == 1) "day" else "days",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        Text(
                            text = if (currentStreakDays > 0) "Keep listening daily!" else "Listen today to start",
                            fontSize = 10.5.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Highest Streak Block
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardDarkPurple.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, goldColor.copy(alpha = if (longestStreakDays > 0) 0.42f else 0.18f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEvents,
                                contentDescription = null,
                                tint = goldColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "HIGHEST STREAK",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = goldColor,
                                letterSpacing = 0.6.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "$longestStreakDays",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = if (longestStreakDays == 1) "day" else "days",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        Text(
                            text = if (longestStreakDays > 0 && currentStreakDays >= longestStreakDays) "New personal record!" else "All-time best run",
                            fontSize = 10.5.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Recent 7-Day Flame Strip
            if (recentDays.isNotEmpty()) {
                val dayFmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BentoCardDarkPurple.copy(alpha = 0.45f),
                    border = BorderStroke(0.8.dp, BentoCardBorder.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        recentDays.forEachIndexed { idx, dayStat ->
                            val isActive = dayStat.durationMs > 0L
                            val isToday = idx == recentDays.lastIndex
                            val shortDay = remember(dayStat.dayStartMs) {
                                dayFmt.format(Date(dayStat.dayStartMs)).take(3)
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isActive) flameColor.copy(alpha = 0.20f)
                                            else BentoCardSurface.copy(alpha = 0.65f)
                                        )
                                        .border(
                                            width = if (isToday) 1.5.dp else 1.dp,
                                            color = when {
                                                isActive -> flameColor.copy(alpha = 0.75f)
                                                isToday -> BentoPrimary.copy(alpha = 0.55f)
                                                else -> BentoCardBorder.copy(alpha = 0.4f)
                                            },
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isActive) {
                                        Icon(
                                            imageVector = Icons.Default.LocalFireDepartment,
                                            contentDescription = "Active",
                                            tint = flameColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(BentoTextSubtle.copy(alpha = 0.45f))
                                        )
                                    }
                                }
                                Text(
                                    text = if (isToday) "Today" else shortDay,
                                    fontSize = 9.5.sp,
                                    fontWeight = if (isToday || isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isActive) BentoTextPrimary else BentoTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


