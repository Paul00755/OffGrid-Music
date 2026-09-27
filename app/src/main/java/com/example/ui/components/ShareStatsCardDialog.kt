package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.WrappedData
import com.example.ui.theme.*
import com.example.util.HapticUtils
import com.example.util.StatsCardImageGenerator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

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
    TODAY("Today", "Today's listening activity", Icons.Default.Today),
    YESTERDAY("Yesterday", "Yesterday's listening activity", Icons.Default.History),
    THIS_WEEK("This Week", "Current week of listening", Icons.Default.ViewWeek),
    LAST_7_DAYS("Last 7 Days", "Past 7 days of listening activity", Icons.Default.DateRange),
    THIS_MONTH("This Month", "Current calendar month", Icons.Default.CalendarMonth),
    LAST_MONTH("Last Month", "Previous calendar month", Icons.Default.Event),
    THIS_YEAR("This Year", "Current calendar year", Icons.Default.CalendarToday),
    ALL_TIME("All Time", "All-time listening journey", Icons.Default.AllInclusive),
    CUSTOM("Choose from Calendar", "Custom date or range from calendar", Icons.Default.EditCalendar);

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryCalendarRangePickerDialog(
    onDismiss: () -> Unit,
    onRangeSelected: (startMs: Long, endMs: Long, label: String) -> Unit
) {
    val dateRangePickerState = rememberDateRangePickerState()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val startUtc = dateRangePickerState.selectedStartDateMillis
                    val endUtc = dateRangePickerState.selectedEndDateMillis ?: startUtc
                    if (startUtc != null) {
                        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = startUtc
                        }
                        val startYear = utcCal.get(Calendar.YEAR)
                        val startMonth = utcCal.get(Calendar.MONTH)
                        val startDay = utcCal.get(Calendar.DAY_OF_MONTH)

                        val localStartCal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, startYear)
                            set(Calendar.MONTH, startMonth)
                            set(Calendar.DAY_OF_MONTH, startDay)
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        val utcEndCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = endUtc ?: startUtc
                        }
                        val endYear = utcEndCal.get(Calendar.YEAR)
                        val endMonth = utcEndCal.get(Calendar.MONTH)
                        val endDay = utcEndCal.get(Calendar.DAY_OF_MONTH)

                        val localEndCal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, endYear)
                            set(Calendar.MONTH, endMonth)
                            set(Calendar.DAY_OF_MONTH, endDay)
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                            add(Calendar.DAY_OF_YEAR, 1)
                        }

                        val label = if (startYear == endYear && startMonth == endMonth && startDay == endDay) {
                            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(localStartCal.time)
                        } else if (startYear == endYear) {
                            val startStr = SimpleDateFormat("MMM d", Locale.getDefault()).format(localStartCal.time)
                            val endVisibleCal = (localEndCal.clone() as Calendar).apply { add(Calendar.MILLISECOND, -1) }
                            val endStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(endVisibleCal.time)
                            "$startStr – $endStr"
                        } else {
                            val startStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(localStartCal.time)
                            val endVisibleCal = (localEndCal.clone() as Calendar).apply { add(Calendar.MILLISECOND, -1) }
                            val endStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(endVisibleCal.time)
                            "$startStr – $endStr"
                        }

                        onRangeSelected(localStartCal.timeInMillis, localEndCal.timeInMillis, label)
                    }
                },
                enabled = dateRangePickerState.selectedStartDateMillis != null
            ) {
                Text("Confirm", color = BentoPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = BentoTextSecondary)
            }
        },
        colors = DatePickerDefaults.colors(
            containerColor = BentoCardSurface
        )
    ) {
        DateRangePicker(
            state = dateRangePickerState,
            title = {
                Text(
                    text = "Select Story Period",
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            headline = {
                val startMillis = dateRangePickerState.selectedStartDateMillis
                val endMillis = dateRangePickerState.selectedEndDateMillis
                val headlineText = when {
                    startMillis != null && endMillis != null -> "Range selected"
                    startMillis != null -> "Select end date (or confirm for single day)"
                    else -> "Select start date"
                }
                Text(
                    text = headlineText,
                    modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
                    fontSize = 12.sp,
                    color = BentoTextSecondary
                )
            },
            showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = BentoCardSurface,
                titleContentColor = BentoTextPrimary,
                headlineContentColor = BentoTextSecondary,
                weekdayContentColor = BentoTextSecondary,
                subheadContentColor = BentoTextSecondary,
                yearContentColor = BentoTextPrimary,
                currentYearContentColor = BentoPrimary,
                selectedYearContentColor = Color.White,
                selectedYearContainerColor = BentoPrimary,
                dayContentColor = BentoTextPrimary,
                selectedDayContentColor = Color.White,
                selectedDayContainerColor = BentoPrimary,
                todayContentColor = BentoPrimary,
                todayDateBorderColor = BentoPrimary,
                dayInSelectionRangeContentColor = Color.White,
                dayInSelectionRangeContainerColor = BentoPrimary.copy(alpha = 0.35f)
            )
        )
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
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(26.dp))
                .border(1.dp, BentoBorder, RoundedCornerShape(26.dp)),
            color = BentoCardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
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
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "Choose timeframe for your Story Card",
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

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Period Option Cards
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StoryPeriod.values().forEach { period ->
                        val isSelected = selectedRange != null && selectedRange.period == period
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    HapticUtils.performClickHaptic(context)
                                    if (period == StoryPeriod.CUSTOM) {
                                        showCalendarPicker = true
                                    } else {
                                        onRangeSelected(StoryTimeRange.forPeriod(period))
                                    }
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) BentoPrimary.copy(alpha = 0.12f) else BentoCardPurple,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.6f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
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
                                        color = if (isSelected) BentoPrimary else BentoPrimary.copy(alpha = 0.12f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = period.icon,
                                                contentDescription = null,
                                                tint = if (isSelected) BentoOnPrimary else BentoPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    Column {
                                        Text(
                                            text = if (isSelected && period == StoryPeriod.CUSTOM && selectedRange?.customLabel != null) {
                                                selectedRange.customLabel
                                            } else {
                                                period.label
                                            },
                                            fontSize = 14.5.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                            color = if (isSelected) BentoPrimary else BentoTextPrimary
                                        )
                                        Text(
                                            text = period.subtitle,
                                            fontSize = 11.5.sp,
                                            color = BentoTextSecondary
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = if (isSelected) Icons.Default.Check else Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = if (isSelected) BentoPrimary else BentoTextSecondary,
                                    modifier = Modifier.size(18.dp)
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
