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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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

    // Midnight boundary anchors for real Period Comparison (Today vs Yesterday, Period vs Previous Period)
    val todayStartMs = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val yesterdayStartMs = remember(todayStartMs) {
        Calendar.getInstance().apply {
            timeInMillis = todayStartMs
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
    }

    val comparisonWindow = remember(selectedPeriod, todayStartMs) {
        fun midnightOffset(daysDelta: Int): Long = Calendar.getInstance().apply {
            timeInMillis = todayStartMs
            add(Calendar.DAY_OF_YEAR, daysDelta)
        }.timeInMillis

        when (selectedPeriod) {
            DetailedStatsPeriod.SEVEN_DAYS -> PeriodComparisonWindow(
                currentLabel = "Last 7 Days",
                previousLabel = "Last Week",
                vsLabel = "vs. Last Week",
                currentStartMs = midnightOffset(-6),
                previousStartMs = midnightOffset(-13),
                previousEndMs = midnightOffset(-6)
            )
            DetailedStatsPeriod.THIRTY_DAYS -> PeriodComparisonWindow(
                currentLabel = "Last 30 Days",
                previousLabel = "Last Month",
                vsLabel = "vs. Last Month",
                currentStartMs = midnightOffset(-29),
                previousStartMs = midnightOffset(-59),
                previousEndMs = midnightOffset(-29)
            )
            DetailedStatsPeriod.NINETY_DAYS -> PeriodComparisonWindow(
                currentLabel = "Last 3 Months",
                previousLabel = "Prior 3 Months",
                vsLabel = "vs. Prior 3 Months",
                currentStartMs = midnightOffset(-89),
                previousStartMs = midnightOffset(-179),
                previousEndMs = midnightOffset(-89)
            )
            DetailedStatsPeriod.ALL_TIME -> PeriodComparisonWindow(
                currentLabel = "This Month",
                previousLabel = "Last Month",
                vsLabel = "vs. Last Month",
                currentStartMs = midnightOffset(-29),
                previousStartMs = midnightOffset(-59),
                previousEndMs = midnightOffset(-29)
            )
        }
    }

    val todayDurationFlow = remember(todayStartMs) { getDurationPlayedBetween(todayStartMs, Long.MAX_VALUE) }
    val todayDurationMs by todayDurationFlow.collectAsState(initial = 0L)
    val todayPlaysFlow = remember(todayStartMs) { getTrackCountBetween(todayStartMs, Long.MAX_VALUE) }
    val todayPlaysCount by todayPlaysFlow.collectAsState(initial = 0)

    val yesterdayDurationFlow = remember(yesterdayStartMs, todayStartMs) {
        getDurationPlayedBetween(yesterdayStartMs, todayStartMs)
    }
    val yesterdayDurationMs by yesterdayDurationFlow.collectAsState(initial = 0L)
    val yesterdayPlaysFlow = remember(yesterdayStartMs, todayStartMs) {
        getTrackCountBetween(yesterdayStartMs, todayStartMs)
    }
    val yesterdayPlaysCount by yesterdayPlaysFlow.collectAsState(initial = 0)

    val compareCurrentDurFlow = remember(comparisonWindow.currentStartMs) {
        getDurationPlayedBetween(comparisonWindow.currentStartMs, Long.MAX_VALUE)
    }
    val compareCurrentDurMs by compareCurrentDurFlow.collectAsState(initial = 0L)
    val compareCurrentPlaysFlow = remember(comparisonWindow.currentStartMs) {
        getTrackCountBetween(comparisonWindow.currentStartMs, Long.MAX_VALUE)
    }
    val compareCurrentPlaysCount by compareCurrentPlaysFlow.collectAsState(initial = 0)

    val comparePrevDurFlow = remember(comparisonWindow.previousStartMs, comparisonWindow.previousEndMs) {
        getDurationPlayedBetween(comparisonWindow.previousStartMs, comparisonWindow.previousEndMs)
    }
    val comparePrevDurMs by comparePrevDurFlow.collectAsState(initial = 0L)
    val comparePrevPlaysFlow = remember(comparisonWindow.previousStartMs, comparisonWindow.previousEndMs) {
        getTrackCountBetween(comparisonWindow.previousStartMs, comparisonWindow.previousEndMs)
    }
    val comparePrevPlaysCount by comparePrevPlaysFlow.collectAsState(initial = 0)

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

    // Compute Wrapped Data strictly from real recorded listening data for the active period
    val wrappedData = remember(periodLoadedWrappedData, totalDurationMs, trackCount, uniqueTrackCount, topSongs, topArtists, topAlbums, timeOfDay, dailyStats, currentStreakDays, longestStreakDays) {
        if (periodLoadedWrappedData != null) {
            return@remember periodLoadedWrappedData!!.copy(
                currentStreakDays = maxOf(periodLoadedWrappedData!!.currentStreakDays, currentStreakDays),
                longestStreakDays = maxOf(periodLoadedWrappedData!!.longestStreakDays, longestStreakDays)
            )
        }
        val effectiveTopSong = topSongs.firstOrNull()
        val totalMins = totalDurationMs / (1000L * 60L)
        val activeDays = dailyStats.count { it.durationMs > 0L }

        val (archetype, desc) = if (totalDurationMs == 0L && trackCount == 0) {
            Pair(
                "No Listening Yet",
                "Play your favorite tracks to unlock real listening insights for this period."
            )
        } else {
            when (timeOfDay.dominantVibe) {
                "Night Owl" -> Pair("Night Owl Explorer", "Your peak hours are late at night with immersive, uninterrupted listening sessions.")
                "Early Bird" -> Pair("Dawn Melodist", "You start your mornings energized with music that sets the rhythm for your day.")
                "Daytime Groover" -> Pair("Midday Flow Master", "Music fuels your daytime focus, keeping your productivity high and spirits upbeat.")
                else -> Pair("Sonic Harmonizer", "You unwind in the evenings with rich soundscapes and eclectic favorites.")
            }
        }

        val topSongPlays = effectiveTopSong?.playCount ?: 0
        val topSongDuration = if (effectiveTopSong != null && topSongPlays > 0 && effectiveTopSong.durationMs > 0L) {
            (topSongPlays.toLong() * effectiveTopSong.durationMs).coerceAtMost(totalDurationMs)
        } else {
            0L
        }

        WrappedData(
            totalMinutes = totalMins,
            totalTracks = trackCount,
            uniqueTracks = uniqueTrackCount,
            activeDaysCount = activeDays,
            currentStreakDays = currentStreakDays,
            longestStreakDays = longestStreakDays,
            topSong = effectiveTopSong,
            topSongPlayCount = topSongPlays,
            topSongDurationMs = topSongDuration,
            topSongs = topSongs,
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

            // Dedicated Listening Streak Showcase Card (Current Streak, Highest Streak & Full Scrollable History)
            item(key = "stats_listening_streak", contentType = "stats_listening_streak") {
                AnimatedStatsSection(visible = screenEntered, delayMs = 45) {
                    ListeningStreakShowcaseCard(
                        currentStreakDays = currentStreakDays,
                        longestStreakDays = longestStreakDays,
                        recentDays = if (heatmapStats.isNotEmpty()) heatmapStats else dailyStats
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

            // Real Period Comparison Card (Today vs Yesterday & Active Period vs Previous Period)
            item(key = "stats_period_comparison", contentType = "stats_period_comparison") {
                AnimatedStatsSection(visible = screenEntered, delayMs = 105) {
                    ListeningComparisonShowcaseCard(
                        todayDurationMs = todayDurationMs,
                        todayPlays = todayPlaysCount,
                        yesterdayDurationMs = yesterdayDurationMs,
                        yesterdayPlays = yesterdayPlaysCount,
                        periodCurrentLabel = comparisonWindow.currentLabel,
                        periodPreviousLabel = comparisonWindow.previousLabel,
                        periodVsLabel = comparisonWindow.vsLabel,
                        periodCurrentDurationMs = compareCurrentDurMs,
                        periodCurrentPlays = compareCurrentPlaysCount,
                        periodPreviousDurationMs = comparePrevDurMs,
                        periodPreviousPlays = comparePrevPlaysCount
                    )
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AccessTime,
                                            contentDescription = null,
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        if (selectedPeriod != DetailedStatsPeriod.ALL_TIME && (compareCurrentDurMs > 0L || comparePrevDurMs > 0L)) {
                                            val deltaMs = compareCurrentDurMs - comparePrevDurMs
                                            val compactBadgeColor = when {
                                                deltaMs > 0L -> Color(0xFF22C55E)
                                                deltaMs < 0L -> Color(0xFFEF5350)
                                                else -> BentoTextSecondary
                                            }
                                            val compactBadgeText = when {
                                                comparePrevDurMs == 0L && compareCurrentDurMs > 0L -> "New"
                                                deltaMs == 0L -> "0%"
                                                else -> {
                                                    val pct = ((abs(deltaMs).toDouble() / comparePrevDurMs.toDouble()) * 100.0).roundToInt().coerceAtMost(999)
                                                    if (deltaMs > 0L) "+$pct%" else "-$pct%"
                                                }
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = compactBadgeColor.copy(alpha = 0.14f)
                                            ) {
                                                Text(
                                                    text = compactBadgeText,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = compactBadgeColor,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
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
    val isDark = BentoBackground.luminance() < 0.5f
    val onAccentPink = if (BentoAccentPinkText.luminance() > 0.45f) Color.Black else Color.White
    val cardBaseColor = if (isDark) {
        if (glassConfig.glassEnabled) BentoCardSurface else Color(0xFF140F22)
    } else {
        BentoCardSurface
    }
    val cardGradientColors = if (isDark) {
        if (glassConfig.gradientEnabled || glassConfig.glassEnabled) {
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
    } else {
        listOf(
            androidx.compose.ui.graphics.lerp(Color.White, BentoPrimary, 0.11f).copy(alpha = 0.94f),
            androidx.compose.ui.graphics.lerp(Color.White, BentoAccentPinkText, 0.07f).copy(alpha = 0.92f),
            BentoCardSurface.copy(alpha = 0.96f)
        )
    }
    val primaryInk = if (isDark) Color.White else BentoTextPrimary
    val secondaryInk = if (isDark) Color.White.copy(alpha = 0.74f) else BentoTextSecondary
    val subtleSurfaceBg = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.72f)
    val subtleSurfaceBorder = if (isDark) Color.White.copy(alpha = 0.12f) else BentoCardBorder
    val chipSurfaceBg = if (isDark) Color.White.copy(alpha = 0.08f) else BentoTextPrimary.copy(alpha = 0.06f)
    val chipSurfaceBorder = if (isDark) Color.White.copy(alpha = 0.15f) else BentoTextPrimary.copy(alpha = 0.12f)
    val anthemSurfaceBg = if (isDark) Color(0xFF1E1736).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.80f)
    val archetypeIconTint = if (isDark) Color(0xFFFFD54F) else Color(0xFFD97706)

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardBaseColor,
        border = BorderStroke(
            width = 1.5.dp,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    BentoAccentPinkText.copy(alpha = if (isDark) 0.85f else 0.50f),
                    BentoPrimary.copy(alpha = if (isDark) 0.75f else 0.45f),
                    BentoPrimary.copy(alpha = if (isDark) 0.45f else 0.25f)
                )
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (glassConfig.glassEnabled || !isDark) 4.dp else 16.dp, RoundedCornerShape(24.dp))
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
                        color = BentoAccentPinkText.copy(alpha = if (isDark) 0.18f else 0.12f),
                        border = BorderStroke(1.dp, BentoAccentPinkText.copy(alpha = if (isDark) 0.4f else 0.32f))
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
                        color = chipSurfaceBg,
                        border = BorderStroke(1.dp, chipSurfaceBorder)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = archetypeIconTint,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = listenerArchetype,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryInk.copy(alpha = 0.95f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Lifetime Playtime Hero Section
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = subtleSurfaceBg,
                    border = BorderStroke(1.dp, subtleSurfaceBorder),
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
                                    color = secondaryInk
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = formatListeningDuration(lifetimePlaytimeMs),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                color = primaryInk,
                                letterSpacing = (-0.6).sp
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BentoPrimary.copy(alpha = if (isDark) 0.2f else 0.14f),
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
                                color = chipSurfaceBg,
                                border = BorderStroke(1.dp, chipSurfaceBorder)
                            ) {
                                Text(
                                    text = "$lifetimeUniqueCount unique songs",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = secondaryInk,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                // Most Played Song Hero Banner
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = anthemSurfaceBg,
                    border = BorderStroke(1.dp, BentoAccentPinkText.copy(alpha = if (isDark) 0.4f else 0.30f)),
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
                                    color = secondaryInk
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
                                            color = primaryInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${mostPlayedSong.displayArtist} • ${formatListeningDuration(mostPlayedSong.playCount * mostPlayedSong.durationMs)}",
                                            fontSize = 12.sp,
                                            color = secondaryInk,
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
                                            tint = if (isThisSongPlaying) onAccentPink else BentoOnPrimary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Play tracks to crown your all-time favorite musical anthem!",
                                fontSize = 12.sp,
                                color = secondaryInk,
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
                            contentColor = onAccentPink
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = onAccentPink,
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

            val isDarkHeatmap = BentoBackground.luminance() < 0.5f
            val emptyCellColor = if (isDarkHeatmap) {
                Color(0xFF1E2230)
            } else {
                BentoTextPrimary.copy(alpha = 0.08f)
            }
            val tier1Color = accentColor.copy(alpha = if (isDarkHeatmap) 0.30f else 0.30f)
            val tier2Color = accentColor.copy(alpha = if (isDarkHeatmap) 0.52f else 0.54f)
            val tier3Color = accentColor.copy(alpha = if (isDarkHeatmap) 0.76f else 0.78f)
            val selectedCellBorderColor = BentoTextPrimary
            val compactLabelStyle = LocalTextStyle.current.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )

            // Selected Day Info Pill
            selectedDay?.let { day ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDarkHeatmap) BentoCardDarkPurple else BentoPrimary.copy(alpha = 0.11f),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = if (isDarkHeatmap) 0.25f else 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (day.isToday) "Today • ${day.fullDate}" else "${day.dayLabel} • ${day.fullDate}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "${formatListeningDuration(day.durationMs)} (${day.trackCount} track${if (day.trackCount != 1) "s" else ""})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
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
                // Day-of-week labels column (Mon, Wed, Fri) with includeFontPadding=false so text never clips
                Column(
                    modifier = Modifier
                        .width(26.dp)
                        .padding(top = 18.dp, end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val dayLabels = listOf("Mon", "", "Wed", "", "Fri", "", "")
                    dayLabels.forEach { label ->
                        Box(
                            modifier = Modifier.height(12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (label.isNotEmpty()) {
                                Text(
                                    text = label,
                                    fontSize = 9.sp,
                                    lineHeight = 10.sp,
                                    style = compactLabelStyle,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
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
                                        lineHeight = 10.sp,
                                        style = compactLabelStyle,
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
                        val borderStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4.dp.toPx())

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
                                        color = selectedCellBorderColor,
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

            // Bottom-right "Less [][][][][] More" legend using theme-aware heatmap ramp
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
                    emptyCellColor,
                    tier1Color,
                    tier2Color,
                    tier3Color,
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

@Immutable
private data class PeriodComparisonWindow(
    val currentLabel: String,
    val previousLabel: String,
    val vsLabel: String,
    val currentStartMs: Long,
    val previousStartMs: Long,
    val previousEndMs: Long
)

@Composable
fun ListeningComparisonShowcaseCard(
    todayDurationMs: Long,
    todayPlays: Int,
    yesterdayDurationMs: Long,
    yesterdayPlays: Int,
    periodCurrentLabel: String,
    periodPreviousLabel: String,
    periodVsLabel: String,
    periodCurrentDurationMs: Long,
    periodCurrentPlays: Int,
    periodPreviousDurationMs: Long,
    periodPreviousPlays: Int,
    modifier: Modifier = Modifier
) {
    BentoCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("listening_comparison_card"),
        backgroundColor = BentoCardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = BentoPrimary.copy(alpha = 0.16f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                contentDescription = "Listening comparison",
                                tint = BentoPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Listening Pace",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "How much more or less you've listened",
                            fontSize = 11.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
            }

            PeriodComparisonBlock(
                title = "Today vs. Yesterday",
                currentLabel = "Today",
                previousLabel = "Yesterday",
                currentDurationMs = todayDurationMs,
                currentPlays = todayPlays,
                previousDurationMs = yesterdayDurationMs,
                previousPlays = yesterdayPlays,
                modifier = Modifier.testTag("comparison_today_vs_yesterday")
            )

            PeriodComparisonBlock(
                title = "$periodCurrentLabel $periodVsLabel",
                currentLabel = periodCurrentLabel,
                previousLabel = periodPreviousLabel,
                currentDurationMs = periodCurrentDurationMs,
                currentPlays = periodCurrentPlays,
                previousDurationMs = periodPreviousDurationMs,
                previousPlays = periodPreviousPlays,
                modifier = Modifier.testTag("comparison_period_vs_previous")
            )
        }
    }
}

@Composable
private fun PeriodComparisonBlock(
    title: String,
    currentLabel: String,
    previousLabel: String,
    currentDurationMs: Long,
    currentPlays: Int,
    previousDurationMs: Long,
    previousPlays: Int,
    modifier: Modifier = Modifier
) {
    val deltaMs = currentDurationMs - previousDurationMs
    val deltaPlays = currentPlays - previousPlays
    val positiveGreen = Color(0xFF22C55E)
    val negativeCoral = Color(0xFFEF5350)

    val isMore = deltaMs > 0L
    val isLess = deltaMs < 0L
    val accentColor = when {
        isMore -> positiveGreen
        isLess -> negativeCoral
        else -> BentoTextSecondary
    }

    val trendIcon = when {
        isMore -> Icons.AutoMirrored.Filled.TrendingUp
        isLess -> Icons.AutoMirrored.Filled.TrendingDown
        else -> Icons.AutoMirrored.Filled.TrendingFlat
    }

    val deltaSummary = remember(currentDurationMs, previousDurationMs, deltaMs) {
        when {
            currentDurationMs == 0L && previousDurationMs == 0L -> "No plays yet"
            previousDurationMs == 0L && currentDurationMs > 0L -> "+${formatListeningDuration(currentDurationMs)} • New"
            deltaMs == 0L -> "Same pace"
            else -> {
                val absDur = formatListeningDuration(abs(deltaMs))
                val pct = ((abs(deltaMs).toDouble() / previousDurationMs.toDouble()) * 100.0).roundToInt()
                val pctText = if (pct > 999) ">999%" else "$pct%"
                if (deltaMs > 0L) "+$absDur ($pctText more)" else "-$absDur ($pctText less)"
            }
        }
    }

    val maxMs = maxOf(currentDurationMs, previousDurationMs, 1L)
    val currentRatio by animateFloatAsState(
        targetValue = if (currentDurationMs == 0L) 0f else (currentDurationMs.toFloat() / maxMs.toFloat()).coerceIn(0.06f, 1f),
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "comparison_current_bar"
    )
    val previousRatio by animateFloatAsState(
        targetValue = if (previousDurationMs == 0L) 0f else (previousDurationMs.toFloat() / maxMs.toFloat()).coerceIn(0.06f, 1f),
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "comparison_previous_bar"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = BentoCardDarkPurple.copy(alpha = 0.58f),
        border = BorderStroke(
            width = 1.dp,
            color = if (isMore || isLess) accentColor.copy(alpha = 0.28f) else BentoCardBorder.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accentColor.copy(alpha = 0.14f),
                    border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = trendIcon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = deltaSummary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor,
                            maxLines = 1
                        )
                    }
                }
            }

            // Current period bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentLabel,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "${formatListeningDuration(currentDurationMs)} • $currentPlays ${if (currentPlays == 1) "play" else "plays"}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoPrimary
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(BentoCardSurface.copy(alpha = 0.85f))
                ) {
                    if (currentRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(currentRatio)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(BentoPrimary, BentoPrimary.copy(alpha = 0.72f))
                                    )
                                )
                        )
                    }
                }
            }

            // Previous period bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = previousLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = BentoTextSecondary
                    )
                    val playsDiffLabel = when {
                        currentPlays == 0 && previousPlays == 0 -> ""
                        deltaPlays > 0 -> " (+$deltaPlays plays)"
                        deltaPlays < 0 -> " ($deltaPlays plays)"
                        else -> " (same plays)"
                    }
                    Text(
                        text = "${formatListeningDuration(previousDurationMs)} • $previousPlays ${if (previousPlays == 1) "play" else "plays"}$playsDiffLabel",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(BentoCardSurface.copy(alpha = 0.85f))
                ) {
                    if (previousRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(previousRatio)
                                .clip(RoundedCornerShape(4.dp))
                                .background(BentoTextSecondary.copy(alpha = 0.42f))
                        )
                    }
                }
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
    val missedColor = Color(0xFFEF5350)

    // Trim empty days prior to user's very first recorded listening day while preserving at least 14 days for scrolling
    val displayDays = remember(recentDays) {
        if (recentDays.isEmpty()) return@remember emptyList()
        val firstActiveIdx = recentDays.indexOfFirst { it.durationMs > 0L }
        if (firstActiveIdx < 0) {
            recentDays.takeLast(14)
        } else {
            val startIdx = minOf(firstActiveIdx, (recentDays.size - 14).coerceAtLeast(0))
            recentDays.subList(startIdx, recentDays.size)
        }
    }

    // Identify the index range of the longest streak run in displayDays for Best Run jump + gold ring highlight
    val bestRunRange = remember(displayDays) {
        var bestStart = -1
        var bestEnd = -1
        var bestLen = 0
        var runStart = -1
        for (i in displayDays.indices) {
            if (displayDays[i].durationMs > 0L) {
                if (runStart < 0) runStart = i
                val len = i - runStart + 1
                if (len >= bestLen) {
                    bestLen = len
                    bestStart = runStart
                    bestEnd = i
                }
            } else {
                runStart = -1
            }
        }
        if (bestLen > 0) bestStart..bestEnd else null
    }

    val activeDaysCount = remember(displayDays) { displayDays.count { it.durationMs > 0L } }
    val missedDaysCount = remember(displayDays) { displayDays.count { it.durationMs == 0L } }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var selectedDayStartMs by remember { mutableStateOf<Long?>(null) }

    // Default scroll position to Today (rightmost end) so user can scroll backward/forward naturally
    LaunchedEffect(displayDays.size) {
        if (displayDays.isNotEmpty()) {
            listState.scrollToItem(displayDays.lastIndex)
            if (selectedDayStartMs == null || displayDays.none { it.dayStartMs == selectedDayStartMs }) {
                selectedDayStartMs = displayDays.last().dayStartMs
            }
        }
    }

    val selectedDay = remember(displayDays, selectedDayStartMs) {
        displayDays.firstOrNull { it.dayStartMs == selectedDayStartMs } ?: displayDays.lastOrNull()
    }
    val selectedDayIndex = remember(displayDays, selectedDay) {
        if (selectedDay == null) -1 else displayDays.indexOfFirst { it.dayStartMs == selectedDay.dayStartMs }
    }

    BentoCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("listening_streak_card"),
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = flameColor.copy(alpha = 0.16f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = "Streak",
                                tint = flameColor,
                                modifier = Modifier.size(19.dp)
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
                            text = if (displayDays.isNotEmpty()) {
                                "$activeDaysCount active • $missedDaysCount missed (${displayDays.size}d span)"
                            } else {
                                "Play a track today to ignite your streak"
                            },
                            fontSize = 11.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

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
                    color = BentoCardDarkPurple.copy(alpha = 0.68f),
                    border = BorderStroke(1.dp, flameColor.copy(alpha = if (currentStreakDays > 0) 0.45f else 0.18f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        flameColor.copy(alpha = if (currentStreakDays > 0) 0.10f else 0.03f),
                                        Color.Transparent
                                    )
                                )
                            )
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
                }

                // Highest Streak Block
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardDarkPurple.copy(alpha = 0.68f),
                    border = BorderStroke(1.dp, goldColor.copy(alpha = if (longestStreakDays > 0) 0.45f else 0.18f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        goldColor.copy(alpha = if (longestStreakDays > 0) 0.10f else 0.03f),
                                        Color.Transparent
                                    )
                                )
                            )
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
            }

            // Full Horizontally Scrollable Streak & Missed-Day Timeline
            if (displayDays.isNotEmpty()) {
                val weekdayFmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
                val dayNumFmt = remember { SimpleDateFormat("d", Locale.getDefault()) }
                val monthShortFmt = remember { SimpleDateFormat("MMM", Locale.getDefault()) }
                val fullDateFmt = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardDarkPurple.copy(alpha = 0.50f),
                    border = BorderStroke(0.8.dp, BentoCardBorder.copy(alpha = 0.50f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Selected Day Inspector + Quick Jump Actions
                        if (selectedDay != null) {
                            val isSelectedActive = selectedDay.durationMs > 0L
                            val isSelectedToday = selectedDayIndex == displayDays.lastIndex
                            val isSelectedYesterday = selectedDayIndex == displayDays.lastIndex - 1
                            val formattedTitle = remember(selectedDay.dayStartMs, isSelectedToday, isSelectedYesterday) {
                                val base = fullDateFmt.format(Date(selectedDay.dayStartMs))
                                when {
                                    isSelectedToday -> "Today • $base"
                                    isSelectedYesterday -> "Yesterday • $base"
                                    else -> base
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = formattedTitle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (isSelectedActive) flameColor.copy(alpha = 0.16f) else missedColor.copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = if (isSelectedActive) "STREAK" else "MISSED",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isSelectedActive) flameColor else missedColor,
                                                letterSpacing = 0.5.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isSelectedActive) {
                                            "${formatListeningDuration(selectedDay.durationMs)} • ${selectedDay.trackCount} ${if (selectedDay.trackCount == 1) "play" else "plays"}"
                                        } else {
                                            "No listening recorded on this day"
                                        },
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Quick-jump chips: Best Run & Today
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (bestRunRange != null && longestStreakDays > 1) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = goldColor.copy(alpha = 0.14f),
                                            border = BorderStroke(0.8.dp, goldColor.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .testTag("streak_jump_best_button")
                                                .clip(RoundedCornerShape(50))
                                                .clickable {
                                                    val targetIdx = bestRunRange.first
                                                    selectedDayStartMs = displayDays[targetIdx].dayStartMs
                                                    coroutineScope.launch {
                                                        listState.animateScrollToItem(targetIdx)
                                                    }
                                                }
                                        ) {
                                            Text(
                                                text = "Best",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = goldColor,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = BentoPrimary.copy(alpha = 0.14f),
                                        border = BorderStroke(0.8.dp, BentoPrimary.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .testTag("streak_jump_today_button")
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                val lastIdx = displayDays.lastIndex
                                                selectedDayStartMs = displayDays[lastIdx].dayStartMs
                                                coroutineScope.launch {
                                                    listState.animateScrollToItem(lastIdx)
                                                }
                                            }
                                    ) {
                                        Text(
                                            text = "Today",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Scrollable Horizontal Streak & Missed Timeline
                        LazyRow(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("streak_scrollable_timeline")
                        ) {
                            itemsIndexed(
                                items = displayDays,
                                key = { _, item -> item.dayStartMs }
                            ) { idx, dayStat ->
                                val isActive = dayStat.durationMs > 0L
                                val isToday = idx == displayDays.lastIndex
                                val isSelected = selectedDay?.dayStartMs == dayStat.dayStartMs
                                val isInBestRun = bestRunRange != null && idx in bestRunRange
                                val prevActive = idx > 0 && displayDays[idx - 1].durationMs > 0L
                                val nextActive = idx < displayDays.lastIndex && displayDays[idx + 1].durationMs > 0L

                                val showMonthTag = remember(dayStat.dayStartMs, idx) {
                                    if (idx == 0) true
                                    else {
                                        val currM = monthShortFmt.format(Date(dayStat.dayStartMs))
                                        val prevM = monthShortFmt.format(Date(displayDays[idx - 1].dayStartMs))
                                        currM != prevM
                                    }
                                }
                                val monthLabel = remember(dayStat.dayStartMs) {
                                    monthShortFmt.format(Date(dayStat.dayStartMs)).uppercase(Locale.getDefault())
                                }
                                val shortDay = remember(dayStat.dayStartMs) {
                                    weekdayFmt.format(Date(dayStat.dayStartMs)).take(3)
                                }
                                val dayNum = remember(dayStat.dayStartMs) {
                                    dayNumFmt.format(Date(dayStat.dayStartMs))
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .width(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) BentoPrimary.copy(alpha = 0.12f) else Color.Transparent
                                        )
                                        .clickable { selectedDayStartMs = dayStat.dayStartMs }
                                        .padding(vertical = 6.dp)
                                        .testTag("streak_day_node_$idx")
                                ) {
                                    // Month indicator header when month transitions
                                    Text(
                                        text = if (showMonthTag) monthLabel else "",
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = BentoPrimary.copy(alpha = 0.85f),
                                        letterSpacing = 0.5.sp,
                                        maxLines = 1
                                    )

                                    // Connector line + Circular Day Node
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(34.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // Left streak connector
                                        if (idx > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterStart)
                                                    .fillMaxWidth(0.5f)
                                                    .height(if (isActive && prevActive) 3.dp else 1.5.dp)
                                                    .background(
                                                        if (isActive && prevActive) flameColor.copy(alpha = 0.72f)
                                                        else BentoCardBorder.copy(alpha = 0.30f)
                                                    )
                                            )
                                        }
                                        // Right streak connector
                                        if (idx < displayDays.lastIndex) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .fillMaxWidth(0.5f)
                                                    .height(if (isActive && nextActive) 3.dp else 1.5.dp)
                                                    .background(
                                                        if (isActive && nextActive) flameColor.copy(alpha = 0.72f)
                                                        else BentoCardBorder.copy(alpha = 0.30f)
                                                    )
                                            )
                                        }

                                        // Day Circle
                                        Box(
                                            modifier = Modifier
                                                .size(if (isSelected) 34.dp else 30.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        isActive -> flameColor.copy(alpha = if (isSelected) 0.30f else 0.20f)
                                                        else -> missedColor.copy(alpha = if (isSelected) 0.16f else 0.08f)
                                                    }
                                                )
                                                .border(
                                                    width = when {
                                                        isSelected -> 2.dp
                                                        isInBestRun && isActive -> 1.5.dp
                                                        isToday -> 1.5.dp
                                                        else -> 1.dp
                                                    },
                                                    color = when {
                                                        isSelected -> BentoPrimary
                                                        isInBestRun && isActive -> goldColor.copy(alpha = 0.85f)
                                                        isActive -> flameColor.copy(alpha = 0.75f)
                                                        isToday -> BentoPrimary.copy(alpha = 0.60f)
                                                        else -> missedColor.copy(alpha = 0.32f)
                                                    },
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isActive) {
                                                Icon(
                                                    imageVector = Icons.Default.LocalFireDepartment,
                                                    contentDescription = "Active streak day",
                                                    tint = if (isInBestRun) goldColor else flameColor,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Missed day",
                                                    tint = missedColor.copy(alpha = 0.65f),
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = if (isToday) "Today" else shortDay,
                                        fontSize = 9.5.sp,
                                        fontWeight = if (isToday || isSelected || isActive) FontWeight.Bold else FontWeight.Medium,
                                        color = when {
                                            isSelected -> BentoPrimary
                                            isActive -> BentoTextPrimary
                                            else -> BentoTextSecondary
                                        },
                                        maxLines = 1
                                    )

                                    Text(
                                        text = dayNum,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = BentoTextSubtle,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // Compact Legend + Scroll Hint
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocalFireDepartment,
                                        contentDescription = null,
                                        tint = flameColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(text = "Active", fontSize = 9.5.sp, color = BentoTextSecondary)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        tint = missedColor.copy(alpha = 0.75f),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(text = "Missed", fontSize = 9.5.sp, color = BentoTextSecondary)
                                }
                                if (bestRunRange != null && longestStreakDays > 1) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(goldColor)
                                        )
                                        Text(text = "Best Run", fontSize = 9.5.sp, color = BentoTextSecondary)
                                    }
                                }
                            }
                            Text(
                                text = "Swipe ↔",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextSubtle
                            )
                        }
                    }
                }
            }
        }
    }
}


