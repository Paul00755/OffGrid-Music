package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.WrappedData
import com.example.ui.theme.*
import com.example.util.HapticUtils
import com.example.util.StatsCardImageGenerator
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class StoryTimeRange(
    val period: StoryPeriod,
    val customStartMs: Long? = null,
    val customEndMs: Long? = null,
    val customLabel: String? = null
) {
    val displayLabel: String
        get() = when (period) {
            StoryPeriod.CUSTOM -> customLabel ?: "Custom Range"
            else -> period.label
        }

    fun getTimeRange(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        if (period == StoryPeriod.CUSTOM && customStartMs != null && customEndMs != null) {
            return Pair(customStartMs, customEndMs)
        }
        return period.getTimeRange(nowMs)
    }

    companion object {
        val TODAY = StoryTimeRange(StoryPeriod.TODAY)
        val YESTERDAY = StoryTimeRange(StoryPeriod.YESTERDAY)
        val THIS_WEEK = StoryTimeRange(StoryPeriod.THIS_WEEK)
        val LAST_7_DAYS = StoryTimeRange(StoryPeriod.LAST_7_DAYS)
        val THIS_MONTH = StoryTimeRange(StoryPeriod.THIS_MONTH)
        val LAST_MONTH = StoryTimeRange(StoryPeriod.LAST_MONTH)
        val THIS_YEAR = StoryTimeRange(StoryPeriod.THIS_YEAR)
        val ALL_TIME = StoryTimeRange(StoryPeriod.ALL_TIME)

        fun forPeriod(period: StoryPeriod): StoryTimeRange = StoryTimeRange(period)
        fun custom(startMs: Long, endMs: Long, label: String): StoryTimeRange =
            StoryTimeRange(StoryPeriod.CUSTOM, startMs, endMs, label)
    }
}

enum class StoryPeriod(val label: String, val subtitle: String, val icon: ImageVector) {
    TODAY("Today", "Today's activity", Icons.Default.Today),
    YESTERDAY("Yesterday", "Yesterday's plays", Icons.Default.History),
    THIS_WEEK("This Week", "Current week", Icons.Default.ViewWeek),
    LAST_7_DAYS("Last 7 Days", "Past 7 days", Icons.Default.DateRange),
    THIS_MONTH("This Month", "Current month", Icons.Default.CalendarMonth),
    LAST_MONTH("Last Month", "Previous month", Icons.Default.Event),
    THIS_YEAR("This Year", "Current year", Icons.Default.CalendarToday),
    ALL_TIME("All Time", "Complete history", Icons.Default.AllInclusive),
    CUSTOM("Choose from Calendar", "Pick any past date or date range (from 2025)", Icons.Default.EditCalendar);

    fun getTimeRange(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        return when (this) {
            TODAY -> {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (startCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                Pair(startCal.timeInMillis, endCal.timeInMillis)
            }
            YESTERDAY -> {
                val todayStart = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val yesterdayStart = (todayStart.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, -1)
                }
                Pair(yesterdayStart.timeInMillis, todayStart.timeInMillis)
            }
            THIS_WEEK -> {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                    if (timeInMillis > nowMs) {
                        add(Calendar.WEEK_OF_YEAR, -1)
                    }
                }
                val endCal = (startCal.clone() as Calendar).apply {
                    add(Calendar.WEEK_OF_YEAR, 1)
                }
                Pair(startCal.timeInMillis, endCal.timeInMillis)
            }
            LAST_7_DAYS -> {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.DAY_OF_YEAR, -6)
                }
                Pair(startCal.timeInMillis, Long.MAX_VALUE)
            }
            THIS_MONTH -> {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (startCal.clone() as Calendar).apply {
                    add(Calendar.MONTH, 1)
                }
                Pair(startCal.timeInMillis, endCal.timeInMillis)
            }
            LAST_MONTH -> {
                val thisMonthStart = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val lastMonthStart = (thisMonthStart.clone() as Calendar).apply {
                    add(Calendar.MONTH, -1)
                }
                Pair(lastMonthStart.timeInMillis, thisMonthStart.timeInMillis)
            }
            THIS_YEAR -> {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (startCal.clone() as Calendar).apply {
                    add(Calendar.YEAR, 1)
                }
                Pair(startCal.timeInMillis, endCal.timeInMillis)
            }
            ALL_TIME -> Pair(0L, Long.MAX_VALUE)
            CUSTOM -> Pair(0L, Long.MAX_VALUE)
        }
    }
}

private const val MIN_ALLOWED_YEAR = 2025

private fun normalizeToStartOfDay(timeMs: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

@Composable
fun StoryCalendarRangePickerDialog(
    onDismiss: () -> Unit,
    onRangeSelected: (startMs: Long, endMs: Long, label: String) -> Unit
) {
    val context = LocalContext.current
    val nowMs = remember { System.currentTimeMillis() }
    val todayCal = remember(nowMs) {
        Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val todayMidnightMs = todayCal.timeInMillis
    val currentYear = remember(todayCal) { todayCal.get(Calendar.YEAR).coerceAtLeast(MIN_ALLOWED_YEAR) }
    val currentMonth = remember(todayCal) { todayCal.get(Calendar.MONTH) }

    val minAllowedMs = remember {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, MIN_ALLOWED_YEAR)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    var isRangeMode by remember { mutableStateOf(true) }
    var displayedYear by remember { mutableIntStateOf(currentYear) }
    var displayedMonth by remember { mutableIntStateOf(currentMonth) }

    // Default initial selection: Today
    var selectedStartMs by remember { mutableStateOf<Long?>(todayMidnightMs) }
    var selectedEndMs by remember { mutableStateOf<Long?>(todayMidnightMs) }

    val shortMonths = remember {
        DateFormatSymbols.getInstance(Locale.getDefault()).shortMonths.take(12)
    }
    val fullMonths = remember {
        DateFormatSymbols.getInstance(Locale.getDefault()).months.take(12)
    }

    val canGoPrevMonth = displayedYear > MIN_ALLOWED_YEAR || (displayedYear == MIN_ALLOWED_YEAR && displayedMonth > Calendar.JANUARY)
    val canGoNextMonth = displayedYear < currentYear || (displayedYear == currentYear && displayedMonth < currentMonth)

    val dayFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val shortDayFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, BentoBorder, RoundedCornerShape(28.dp)),
            color = BentoCardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                // Top Header
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
                            shape = RoundedCornerShape(12.dp),
                            color = BentoPrimary.copy(alpha = 0.16f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.EditCalendar,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Custom Story Calendar",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "Past dates only • $MIN_ALLOWED_YEAR to Today",
                                fontSize = 11.5.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(BentoCardPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mode Switcher: Date Range vs Single Day
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BentoCardPurple,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val modes = listOf(true to "Date Range", false to "Single Date")
                        modes.forEach { (rangeMode, title) ->
                            val active = isRangeMode == rangeMode
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) BentoPrimary else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        HapticUtils.performClickHaptic(context)
                                        isRangeMode = rangeMode
                                        if (!rangeMode && selectedStartMs != null) {
                                            selectedEndMs = selectedStartMs
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (rangeMode) Icons.Default.DateRange else Icons.Default.Today,
                                        contentDescription = null,
                                        tint = if (active) BentoOnPrimary else BentoTextSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = title,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = if (active) BentoOnPrimary else BentoTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Year Selector Strip (strictly 2025 .. currentYear)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "YEAR",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = BentoTextSecondary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (yr in MIN_ALLOWED_YEAR..currentYear) {
                            val isSelectedYear = displayedYear == yr
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelectedYear) BentoPrimary.copy(alpha = 0.2f) else BentoCardPurple,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelectedYear) BentoPrimary else BentoBorder.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        HapticUtils.performClickHaptic(context)
                                        displayedYear = yr
                                        if (yr == currentYear && displayedMonth > currentMonth) {
                                            displayedMonth = currentMonth
                                        }
                                    }
                            ) {
                                Text(
                                    text = yr.toString(),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isSelectedYear) BentoPrimary else BentoTextPrimary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Month Header with Prev/Next Arrows
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BentoCardPurple.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (canGoPrevMonth) {
                                        HapticUtils.performClickHaptic(context)
                                        if (displayedMonth == Calendar.JANUARY) {
                                            displayedYear = (displayedYear - 1).coerceAtLeast(MIN_ALLOWED_YEAR)
                                            displayedMonth = Calendar.DECEMBER
                                        } else {
                                            displayedMonth -= 1
                                        }
                                    }
                                },
                                enabled = canGoPrevMonth,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "Previous Month",
                                    tint = if (canGoPrevMonth) BentoTextPrimary else BentoTextSecondary.copy(alpha = 0.25f)
                                )
                            }

                            Text(
                                text = "${fullMonths.getOrElse(displayedMonth) { "" }} $displayedYear",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoTextPrimary
                            )

                            IconButton(
                                onClick = {
                                    if (canGoNextMonth) {
                                        HapticUtils.performClickHaptic(context)
                                        if (displayedMonth == Calendar.DECEMBER) {
                                            displayedYear = (displayedYear + 1).coerceAtMost(currentYear)
                                            displayedMonth = Calendar.JANUARY
                                        } else {
                                            displayedMonth += 1
                                        }
                                    }
                                },
                                enabled = canGoNextMonth,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Next Month",
                                    tint = if (canGoNextMonth) BentoTextPrimary else BentoTextSecondary.copy(alpha = 0.25f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Fast Horizontal Month Strip (Jan .. Dec)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            shortMonths.forEachIndexed { monthIdx, monthLabel ->
                                val isFutureMonth = displayedYear == currentYear && monthIdx > currentMonth
                                val isSelectedMonth = displayedMonth == monthIdx
                                Surface(
                                    shape = RoundedCornerShape(9.dp),
                                    color = when {
                                        isSelectedMonth -> BentoPrimary
                                        else -> Color.White.copy(alpha = 0.04f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            isSelectedMonth -> BentoPrimary
                                            else -> Color.White.copy(alpha = 0.07f)
                                        }
                                    ),
                                    modifier = Modifier
                                        .alpha(if (isFutureMonth) 0.32f else 1f)
                                        .clip(RoundedCornerShape(9.dp))
                                        .clickable(enabled = !isFutureMonth) {
                                            HapticUtils.performClickHaptic(context)
                                            displayedMonth = monthIdx
                                        }
                                ) {
                                    Text(
                                        text = monthLabel,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelectedMonth) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (isSelectedMonth) BentoOnPrimary else BentoTextPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Calendar Grid Container
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0E0B16),
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        // Weekday Headers
                        val weekdays = listOf("S", "M", "T", "W", "T", "F", "S")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            weekdays.forEach { dayName ->
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dayName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextSecondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Compute Month Days
                        val monthCal = remember(displayedYear, displayedMonth) {
                            Calendar.getInstance().apply {
                                set(Calendar.YEAR, displayedYear)
                                set(Calendar.MONTH, displayedMonth)
                                set(Calendar.DAY_OF_MONTH, 1)
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                        }
                        val firstDayOfWeekOffset = (monthCal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
                        val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        val totalCells = ((firstDayOfWeekOffset + daysInMonth + 6) / 7) * 7
                        val rows = totalCells / 7

                        val effectiveStart = selectedStartMs
                        val effectiveEnd = selectedEndMs ?: selectedStartMs

                        for (row in 0 until rows) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (col in 0 until 7) {
                                    val cellIndex = row * 7 + col
                                    val dayNumber = cellIndex - firstDayOfWeekOffset + 1
                                    if (dayNumber < 1 || dayNumber > daysInMonth) {
                                        Box(modifier = Modifier.weight(1f).height(38.dp))
                                    } else {
                                        val cellMs = remember(displayedYear, displayedMonth, dayNumber) {
                                            Calendar.getInstance().apply {
                                                set(Calendar.YEAR, displayedYear)
                                                set(Calendar.MONTH, displayedMonth)
                                                set(Calendar.DAY_OF_MONTH, dayNumber)
                                                set(Calendar.HOUR_OF_DAY, 0)
                                                set(Calendar.MINUTE, 0)
                                                set(Calendar.SECOND, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }.timeInMillis
                                        }

                                        val isFuture = cellMs > todayMidnightMs
                                        val isBeforeMin = cellMs < minAllowedMs
                                        val isAllowed = !isFuture && !isBeforeMin
                                        val isToday = cellMs == todayMidnightMs

                                        val isStart = effectiveStart != null && cellMs == effectiveStart
                                        val isEnd = effectiveEnd != null && cellMs == effectiveEnd
                                        val isInRange = effectiveStart != null && effectiveEnd != null &&
                                            cellMs >= effectiveStart && cellMs <= effectiveEnd
                                        val isRangeSpan = isInRange && effectiveStart != effectiveEnd

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(38.dp)
                                                .then(
                                                    if (isRangeSpan) {
                                                        val bgShape = when {
                                                            isStart -> RoundedCornerShape(topStart = 19.dp, bottomStart = 19.dp)
                                                            isEnd -> RoundedCornerShape(topEnd = 19.dp, bottomEnd = 19.dp)
                                                            else -> RoundedCornerShape(0.dp)
                                                        }
                                                        Modifier
                                                            .clip(bgShape)
                                                            .background(BentoPrimary.copy(alpha = 0.22f))
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                .clickable(enabled = isAllowed) {
                                                    HapticUtils.performClickHaptic(context)
                                                    if (!isRangeMode) {
                                                        selectedStartMs = cellMs
                                                        selectedEndMs = cellMs
                                                    } else {
                                                        val curStart = selectedStartMs
                                                        val curEnd = selectedEndMs
                                                        if (curStart == null || (curStart != null && curEnd != null && curStart != curEnd)) {
                                                            // Start a fresh range
                                                            selectedStartMs = cellMs
                                                            selectedEndMs = null
                                                        } else {
                                                            // Second tap completes range (auto-orders start & end)
                                                            if (cellMs < curStart) {
                                                                selectedEndMs = curStart
                                                                selectedStartMs = cellMs
                                                            } else {
                                                                selectedEndMs = cellMs
                                                            }
                                                        }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val isEndpoint = isStart || isEnd
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        when {
                                                            isEndpoint -> BentoPrimary
                                                            else -> Color.Transparent
                                                        }
                                                    )
                                                    .then(
                                                        if (isToday && !isEndpoint) {
                                                            Modifier.border(1.dp, BentoPrimary, CircleShape)
                                                        } else {
                                                            Modifier
                                                        }
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = dayNumber.toString(),
                                                    fontSize = 12.5.sp,
                                                    fontWeight = when {
                                                        isEndpoint || isToday -> FontWeight.ExtraBold
                                                        isInRange -> FontWeight.Bold
                                                        else -> FontWeight.Medium
                                                    },
                                                    color = when {
                                                        !isAllowed -> BentoTextSecondary.copy(alpha = 0.24f)
                                                        isEndpoint -> BentoOnPrimary
                                                        isToday -> BentoPrimary
                                                        isInRange -> Color.White
                                                        else -> BentoTextPrimary
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Selection Summary Banner
                val finalStartMs = selectedStartMs
                val finalEndMs = selectedEndMs ?: selectedStartMs
                val selectedDaysCount = if (finalStartMs != null && finalEndMs != null) {
                    (TimeUnit.MILLISECONDS.toDays(finalEndMs - finalStartMs).toInt() + 1).coerceAtLeast(1)
                } else 0

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BentoPrimary.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isRangeMode && selectedEndMs == null && selectedStartMs != null) {
                                    "Tap an end date (or Confirm for single day)"
                                } else {
                                    "SELECTED TIMEFRAME"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                color = BentoPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val summaryText = if (finalStartMs != null && finalEndMs != null) {
                                if (finalStartMs == finalEndMs) {
                                    dayFormat.format(finalStartMs)
                                } else {
                                    "${shortDayFormat.format(finalStartMs)} – ${dayFormat.format(finalEndMs)}"
                                }
                            } else {
                                "Tap a date above"
                            }
                            Text(
                                text = summaryText,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoTextPrimary
                            )
                        }

                        if (selectedDaysCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BentoPrimary.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = if (selectedDaysCount == 1) "1 Day" else "$selectedDaysCount Days",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoPrimary,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, BentoBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoTextSecondary)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            val startDayMs = normalizeToStartOfDay(finalStartMs ?: return@Button)
                            val endDayInclusiveMs = normalizeToStartOfDay(finalEndMs ?: startDayMs)
                                .coerceAtMost(todayMidnightMs)
                            val safeStartMs = startDayMs.coerceIn(minAllowedMs, endDayInclusiveMs)

                            val exclusiveEndCal = Calendar.getInstance().apply {
                                timeInMillis = endDayInclusiveMs
                                add(Calendar.DAY_OF_YEAR, 1)
                            }

                            val startCal = Calendar.getInstance().apply { timeInMillis = safeStartMs }
                            val endCal = Calendar.getInstance().apply { timeInMillis = endDayInclusiveMs }

                            val startYear = startCal.get(Calendar.YEAR)
                            val endYear = endCal.get(Calendar.YEAR)

                            val label = if (safeStartMs == endDayInclusiveMs) {
                                dayFormat.format(startCal.time)
                            } else if (startYear == endYear) {
                                "${shortDayFormat.format(startCal.time)} – ${dayFormat.format(endCal.time)}"
                            } else {
                                "${dayFormat.format(startCal.time)} – ${dayFormat.format(endCal.time)}"
                            }

                            onRangeSelected(safeStartMs, exclusiveEndCal.timeInMillis, label)
                        },
                        enabled = finalStartMs != null,
                        modifier = Modifier
                            .weight(1.35f)
                            .height(46.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply Dates", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SharePeriodSelectionDialog(
    selectedRange: StoryTimeRange? = null,
    onDismiss: () -> Unit,
    onRangeSelected: (StoryTimeRange) -> Unit
) {
    val context = LocalContext.current
    var showCalendarPicker by remember { mutableStateOf(false) }

    if (showCalendarPicker) {
        StoryCalendarRangePickerDialog(
            onDismiss = { showCalendarPicker = false },
            onRangeSelected = { startMs, endMs, label ->
                showCalendarPicker = false
                HapticUtils.performClickHaptic(context)
                onRangeSelected(StoryTimeRange.custom(startMs, endMs, label))
            }
        )
    }

    val presetPeriods = remember {
        listOf(
            StoryPeriod.TODAY,
            StoryPeriod.YESTERDAY,
            StoryPeriod.THIS_WEEK,
            StoryPeriod.LAST_7_DAYS,
            StoryPeriod.THIS_MONTH,
            StoryPeriod.LAST_MONTH,
            StoryPeriod.THIS_YEAR,
            StoryPeriod.ALL_TIME
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, BentoBorder, RoundedCornerShape(28.dp)),
            color = BentoCardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
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
                            shape = RoundedCornerShape(12.dp),
                            color = BentoPrimary.copy(alpha = 0.16f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Select Time Period",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "Choose a timeframe for your Story Card",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(BentoCardPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick Presets Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "QUICK TIMEFRAMES",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = BentoTextSecondary
                    )
                    Text(
                        text = "Instant Recap",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoPrimary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2-Column Bento Grid for Presets
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetPeriods.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { period ->
                                val isSelected = selectedRange != null && selectedRange.period == period
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            HapticUtils.performClickHaptic(context)
                                            onRangeSelected(StoryTimeRange.forPeriod(period))
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSelected) BentoPrimary.copy(alpha = 0.14f) else BentoCardPurple,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.55f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 11.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isSelected) BentoPrimary else BentoPrimary.copy(alpha = 0.12f),
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = period.icon,
                                                        contentDescription = null,
                                                        tint = if (isSelected) BentoOnPrimary else BentoPrimary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }

                                            if (isSelected) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = BentoPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = BentoOnPrimary,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = period.label,
                                            fontSize = 13.5.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                            color = if (isSelected) BentoPrimary else BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = period.subtitle,
                                            fontSize = 10.5.sp,
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

                Spacer(modifier = Modifier.height(14.dp))

                // Custom Calendar Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CUSTOM CALENDAR",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = BentoTextSecondary
                    )
                    Text(
                        text = "2025 – Today",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoPrimary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val isCustomSelected = selectedRange?.period == StoryPeriod.CUSTOM
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            HapticUtils.performClickHaptic(context)
                            showCalendarPicker = true
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = if (isCustomSelected) BentoPrimary.copy(alpha = 0.16f) else BentoCardPurple,
                    border = BorderStroke(
                        1.2.dp,
                        if (isCustomSelected) BentoPrimary else BentoPrimary.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        BentoPrimary.copy(alpha = 0.14f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = BentoPrimary,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.EditCalendar,
                                        contentDescription = null,
                                        tint = BentoOnPrimary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = if (isCustomSelected && !selectedRange?.customLabel.isNullOrBlank()) {
                                        selectedRange?.customLabel ?: "Choose from Calendar"
                                    } else {
                                        "Choose from Calendar"
                                    },
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isCustomSelected) BentoPrimary else BentoTextPrimary
                                )
                                Text(
                                    text = "Pick any past single date or range (2025 – Today)",
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = BentoPrimary.copy(alpha = 0.16f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isCustomSelected) "Change" else "Open",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SharePeriodSelectionDialog(
    onDismiss: () -> Unit,
    onPeriodSelected: (StoryPeriod) -> Unit
) {
    SharePeriodSelectionDialog(
        selectedRange = null,
        onDismiss = onDismiss,
        onRangeSelected = { onPeriodSelected(it.period) }
    )
}

@Composable
fun ShareStatsCardDialog(
    wrappedData: WrappedData? = null,
    timePeriodTitle: String? = null,
    initialPeriod: StoryPeriod = StoryPeriod.ALL_TIME,
    initialRange: StoryTimeRange? = null,
    loadWrappedData: (suspend (Long, Long) -> WrappedData)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val resolvedInitialRange = remember(initialRange, initialPeriod, timePeriodTitle) {
        if (initialRange != null) {
            initialRange
        } else if (timePeriodTitle != null && timePeriodTitle != "All Time" && timePeriodTitle != initialPeriod.label) {
            // Check if title matches one of our known period labels
            val matchingPeriod = StoryPeriod.values().find { it.label.equals(timePeriodTitle, ignoreCase = true) }
            if (matchingPeriod != null) {
                StoryTimeRange.forPeriod(matchingPeriod)
            } else {
                StoryTimeRange.custom(0L, Long.MAX_VALUE, timePeriodTitle)
            }
        } else {
            StoryTimeRange.forPeriod(initialPeriod)
        }
    }

    var currentRange by remember { mutableStateOf(resolvedInitialRange) }
    var effectiveData by remember { mutableStateOf(wrappedData) }
    var cardBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var showPeriodSwitcherMenu by remember { mutableStateOf(false) }

    // Fetch data for selected period when loader is provided
    LaunchedEffect(currentRange) {
        isGenerating = true
        try {
            val (start, end) = currentRange.getTimeRange()
            val data = if (loadWrappedData != null) {
                loadWrappedData(start, end)
            } else {
                wrappedData ?: WrappedData()
            }
            effectiveData = data
            val bmp = StatsCardImageGenerator.generateStatsCardBitmap(
                context = context,
                wrappedData = data,
                timePeriodTitle = currentRange.displayLabel
            )
            cardBitmap = bmp
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Could not render stats card: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isGenerating = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, BentoBorder, RoundedCornerShape(28.dp)),
            color = BentoCardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Share My Stats Card",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            // Period badge that is clickable to switch periods
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BentoPrimary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { showPeriodSwitcherMenu = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = currentRange.displayLabel,
                                        color = BentoPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Switch Period",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Preview Container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF090710))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isGenerating || cardBitmap == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = BentoPrimary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Generating ${currentRange.displayLabel} stats card...",
                                color = BentoTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        cardBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Generated Stats Card Preview",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Save Image Button
                    OutlinedButton(
                        onClick = {
                            val bmp = cardBitmap ?: return@OutlinedButton
                            isSaving = true
                            coroutineScope.launch {
                                val saved = StatsCardImageGenerator.saveImageToGallery(context, bmp)
                                isSaving = false
                                if (saved) {
                                    Toast.makeText(context, "Saved to Pictures/OffGrid!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = cardBitmap != null && !isSaving,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Image", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    // Share Image Button (Primary Action)
                    Button(
                        onClick = {
                            val bmp = cardBitmap ?: return@Button
                            val data = effectiveData ?: return@Button
                            coroutineScope.launch {
                                val uri = StatsCardImageGenerator.saveBitmapToCache(context, bmp)
                                if (uri != null) {
                                    val caption = buildString {
                                        appendLine("🔥 My OffGrid Music Stats (${currentRange.displayLabel}):")
                                        appendLine("⏱ Total Time: ${data.totalMinutes} mins")
                                        if (data.longestStreakDays > 0 || data.currentStreakDays > 0) {
                                            appendLine("⚡ Streak: ${data.currentStreakDays}d Current • ${data.longestStreakDays}d Best")
                                        }
                                        if (data.topSong != null) {
                                            appendLine("👑 #1 Track: ${data.topSong.displayTitle} by ${data.topSong.displayArtist}")
                                        }
                                        appendLine("🎧 Persona: ${data.listenerArchetype}")
                                    }
                                    StatsCardImageGenerator.shareImageCard(context, uri, caption)
                                } else {
                                    Toast.makeText(context, "Unable to prepare image for sharing", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = cardBitmap != null,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = BentoOnPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share Image Card", fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showPeriodSwitcherMenu) {
        SharePeriodSelectionDialog(
            selectedRange = currentRange,
            onDismiss = { showPeriodSwitcherMenu = false },
            onRangeSelected = { newRange ->
                currentRange = newRange
                showPeriodSwitcherMenu = false
            }
        )
    }
}
