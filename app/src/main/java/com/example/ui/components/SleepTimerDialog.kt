package com.example.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.SleepTimerManager
import com.example.service.SleepTimerState
import com.example.ui.theme.*
import com.example.util.HapticUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun SleepTimerDialog(
    timerState: SleepTimerState,
    onStartTimerMinutes: (Int, Boolean) -> Unit,
    onStartSongCount: (Int) -> Unit,
    onAddMinutes: (Int) -> Unit = {},
    onAddSongs: (Int) -> Unit = {},
    onSetFinishLastSong: (Boolean) -> Unit = {},
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(if (timerState.isActive && timerState.mode == "SONGS") 1 else 0) }
    var customHours by remember { mutableStateOf("") }
    var customMinutes by remember { mutableStateOf("") }
    var customSongs by remember { mutableStateOf("") }
    var finishLastSongTime by remember(timerState.finishLastSong) { mutableStateOf(timerState.finishLastSong) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Interactive dial selections (sync with active timer unless the user manually drags the dial)
    var userEditedTimeDial by remember { mutableStateOf(false) }
    var selectedDialMinutes by remember {
        mutableIntStateOf(
            if (timerState.isActive && timerState.mode == "TIME" && timerState.remainingSeconds > 0L) {
                ((timerState.remainingSeconds + 59L) / 60L).toInt().coerceIn(1, SleepTimerManager.MAX_TIME_MINUTES)
            } else {
                30
            }
        )
    }
    var userEditedTrackDial by remember { mutableStateOf(false) }
    var selectedDialTracks by remember {
        mutableIntStateOf(
            if (timerState.isActive && timerState.mode == "SONGS" && timerState.remainingSongs > 0) {
                timerState.remainingSongs.coerceIn(1, SleepTimerManager.MAX_TRACK_COUNT)
            } else {
                5
            }
        )
    }

    LaunchedEffect(timerState.isActive, timerState.mode, timerState.remainingSeconds) {
        if (!userEditedTimeDial && timerState.isActive && timerState.mode == "TIME" && timerState.remainingSeconds > 0L) {
            selectedDialMinutes = ((timerState.remainingSeconds + 59L) / 60L).toInt()
                .coerceIn(1, SleepTimerManager.MAX_TIME_MINUTES)
        }
    }
    LaunchedEffect(timerState.isActive, timerState.mode, timerState.remainingSongs) {
        if (!userEditedTrackDial && timerState.isActive && timerState.mode == "SONGS" && timerState.remainingSongs > 0) {
            selectedDialTracks = timerState.remainingSongs.coerceIn(1, SleepTimerManager.MAX_TRACK_COUNT)
        }
    }

    val is24Hour = remember(context) {
        try {
            DateFormat.is24HourFormat(context)
        } catch (_: Throwable) {
            true
        }
    }
    val timeFormatter = remember(is24Hour) {
        SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
    }

    val predefinedMinutes = remember { listOf(5, 10, 15, 30, 45, 60, 90, 120, 180) }
    val predefinedSongCounts = remember { listOf(1, 2, 5, 10, 25, 50) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
            shadowElevation = 14.dp,
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 20.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BentoPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (selectedTab == 0) Icons.Default.Timer else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sleep Timer",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = if (timerState.isActive) "Timer currently running" else "Auto-pause after time or tracks",
                                fontSize = 12.sp,
                                color = if (timerState.isActive) BentoPrimary else BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDismiss()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close sleep timer dialog",
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Sleek Pill Mode Selector (By Time vs By Tracks)
                Surface(
                    shape = CircleShape,
                    color = BentoCardPurple.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            Triple(0, "By Time", Icons.Default.Timer),
                            Triple(1, "By Tracks", Icons.Default.MusicNote)
                        ).forEach { (idx, label, icon) ->
                            val selected = selectedTab == idx
                            Surface(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    selectedTab = idx
                                    errorMessage = null
                                },
                                shape = CircleShape,
                                color = if (selected) BentoPrimary else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 40.dp)
                                    .testTag(if (idx == 0) "sleep_timer_tab_time" else "sleep_timer_tab_tracks")
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selected) BentoOnPrimary else BentoTextSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) BentoOnPrimary else BentoTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Active Status Banner + Quick Dynamic Extend Controls
                if (timerState.isActive) {
                    ActiveSleepTimerExtensionCard(
                        timerState = timerState,
                        onAddMinutes = { extraMins ->
                            HapticUtils.performClickHaptic(context)
                            errorMessage = null
                            userEditedTimeDial = false
                            onAddMinutes(extraMins)
                        },
                        onAddSongs = { extraSongs ->
                            HapticUtils.performClickHaptic(context)
                            errorMessage = null
                            userEditedTrackDial = false
                            onAddSongs(extraSongs)
                        }
                    )
                }

                if (selectedTab == 0) {
                    // --- BY TIME: Circular Radial Clock Dial (up to 3 hours / 180 min) ---
                    val showingLiveTime = timerState.isActive && timerState.mode == "TIME" && !userEditedTimeDial
                    val effectiveSeconds = if (showingLiveTime) {
                        timerState.remainingSeconds.coerceAtLeast(0L)
                    } else {
                        selectedDialMinutes * 60L
                    }
                    val dialFraction = (effectiveSeconds.toFloat() / SleepTimerManager.MAX_TIME_SECONDS.toFloat())
                        .coerceIn(0f, 1f)

                    val (centerPrimaryText, centerSubText) = if (showingLiveTime) {
                        if (timerState.isWaitingForCurrentTrackToEnd || timerState.remainingSeconds <= 0L) {
                            "Final" to "finishing track"
                        } else {
                            formatCountdownClock(timerState.remainingSeconds) to "remaining"
                        }
                    } else {
                        formatDialMinutesDisplay(selectedDialMinutes)
                    }

                    val stopsAtFormatted = remember(effectiveSeconds, timeFormatter) {
                        val targetEpoch = System.currentTimeMillis() + effectiveSeconds * 1000L
                        timeFormatter.format(Date(targetEpoch))
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadialSleepDial(
                            fraction = dialFraction,
                            centerValue = centerPrimaryText,
                            centerUnit = centerSubText,
                            totalTicks = 36,
                            majorTickEvery = 3,
                            scaleLabels = remember {
                                listOf(
                                    0f to "3h",
                                    30f to "15",
                                    60f to "30",
                                    90f to "45",
                                    120f to "1h",
                                    180f to "1h30",
                                    240f to "2h",
                                    300f to "2h30"
                                )
                            },
                            onFractionChange = { newFraction ->
                                val mins = (newFraction * SleepTimerManager.MAX_TIME_MINUTES)
                                    .roundToInt()
                                    .coerceIn(1, SleepTimerManager.MAX_TIME_MINUTES)
                                if (mins != selectedDialMinutes) {
                                    HapticUtils.performTickHaptic(context)
                                }
                                userEditedTimeDial = true
                                selectedDialMinutes = mins
                                errorMessage = null
                            },
                            modifier = Modifier
                                .size(226.dp)
                                .testTag("sleep_timer_time_dial")
                        )

                        // "STOPS AT HH:mm" indicator
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = BentoTextSecondary,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.2.sp
                                    )
                                ) {
                                    append("STOPS AT  ")
                                }
                                withStyle(
                                    SpanStyle(
                                        color = BentoPrimary,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.6.sp
                                    )
                                ) {
                                    append(
                                        if (showingLiveTime && (timerState.isWaitingForCurrentTrackToEnd || timerState.remainingSeconds <= 0L)) {
                                            "TRACK END"
                                        } else {
                                            stopsAtFormatted
                                        }
                                    )
                                }
                            },
                            fontSize = 12.5.sp,
                            modifier = Modifier.testTag("sleep_timer_stops_at_text")
                        )

                        // Primary CTA to commit dial value
                        Button(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                errorMessage = null
                                onStartTimerMinutes(selectedDialMinutes, finishLastSongTime)
                                onDismiss()
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BentoPrimary,
                                contentColor = BentoOnPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 11.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .testTag("sleep_timer_dial_start_button")
                        ) {
                            val labelDur = formatShortMinuteChip(selectedDialMinutes)
                            Text(
                                text = if (timerState.isActive && timerState.mode == "TIME") {
                                    "Update to $labelDur"
                                } else {
                                    "Start $labelDur Timer"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Quick Duration Pill Presets (matching sleek horizontal/wrapped chips)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Quick Presets",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextSecondary
                        )
                        // Row 1: 5m, 10m, 15m, 30m, 45m
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            predefinedMinutes.take(5).forEach { mins ->
                                val isSelected = selectedDialMinutes == mins
                                SleepPresetChip(
                                    label = formatShortMinuteChip(mins),
                                    selected = isSelected,
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        selectedDialMinutes = mins
                                        userEditedTimeDial = true
                                        onStartTimerMinutes(mins, finishLastSongTime)
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("sleep_timer_preset_min_$mins")
                                )
                            }
                        }
                        // Row 2: 1h, 1h 30m, 2h, 3h
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            predefinedMinutes.drop(5).forEach { mins ->
                                val isSelected = selectedDialMinutes == mins
                                SleepPresetChip(
                                    label = formatShortMinuteChip(mins),
                                    selected = isSelected,
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        selectedDialMinutes = mins
                                        userEditedTimeDial = true
                                        onStartTimerMinutes(mins, finishLastSongTime)
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("sleep_timer_preset_min_$mins")
                                )
                            }
                        }
                    }

                    // Custom Time Input Row (preserved up to 3h / 180 min)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Custom Duration (up to 3h)",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customHours,
                                onValueChange = { raw ->
                                    customHours = raw.filter { it.isDigit() }.take(2)
                                    errorMessage = null
                                },
                                label = { Text("Hrs", fontSize = 11.5.sp) },
                                placeholder = { Text("0") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = customMinutes,
                                onValueChange = { raw ->
                                    customMinutes = raw.filter { it.isDigit() }.take(3)
                                    errorMessage = null
                                },
                                label = { Text("Min", fontSize = 11.5.sp) },
                                placeholder = { Text("20") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val hrs = customHours.toIntOrNull() ?: 0
                                    val mins = customMinutes.toIntOrNull() ?: 0
                                    val totalMins = hrs * 60 + mins
                                    when {
                                        totalMins <= 0 -> {
                                            errorMessage = "Enter at least 1 minute."
                                        }
                                        totalMins > SleepTimerManager.MAX_TIME_MINUTES -> {
                                            errorMessage = "Maximum sleep timer duration is 3 hours (180 minutes)."
                                        }
                                        else -> {
                                            HapticUtils.performClickHaptic(context)
                                            errorMessage = null
                                            selectedDialMinutes = totalMins
                                            onStartTimerMinutes(totalMins, finishLastSongTime)
                                            onDismiss()
                                        }
                                    }
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BentoPrimary,
                                    contentColor = BentoOnPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("sleep_timer_custom_time_set_button")
                            ) {
                                Text("Set", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Bottom Card: "Finish last track" — "Don't cut off mid-track" (inspired by design reference)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = BentoCardPurple.copy(alpha = 0.52f),
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(BentoCardSurface.copy(alpha = 0.9f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Finish last track",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Don't cut off mid-track",
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary
                                )
                            }
                            Switch(
                                checked = finishLastSongTime,
                                onCheckedChange = {
                                    HapticUtils.performClickHaptic(context)
                                    finishLastSongTime = it
                                    onSetFinishLastSong(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = BentoOnPrimary,
                                    checkedTrackColor = BentoPrimary,
                                    uncheckedThumbColor = BentoPrimary.copy(alpha = 0.85f),
                                    uncheckedTrackColor = BentoCardSurface,
                                    uncheckedBorderColor = BentoBorder.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.testTag("sleep_timer_finish_last_track_switch")
                            )
                        }
                    }
                } else {
                    // --- BY TRACKS: Matching Circular Radial Track Dial (1–50 tracks) ---
                    val showingLiveTracks = timerState.isActive && timerState.mode == "SONGS" && !userEditedTrackDial
                    val effectiveTracks = if (showingLiveTracks) {
                        timerState.remainingSongs.coerceIn(0, SleepTimerManager.MAX_TRACK_COUNT)
                    } else {
                        selectedDialTracks
                    }
                    val trackFraction = (effectiveTracks.toFloat() / SleepTimerManager.MAX_TRACK_COUNT.toFloat())
                        .coerceIn(0f, 1f)

                    val (trackPrimary, trackSub) = if (showingLiveTracks) {
                        if (timerState.isFinalSleepTrack) {
                            "Final" to "pausing next"
                        } else {
                            "${timerState.remainingSongs}" to if (timerState.remainingSongs == 1) "track left" else "tracks left"
                        }
                    } else {
                        "$selectedDialTracks" to if (selectedDialTracks == 1) "track" else "tracks"
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadialSleepDial(
                            fraction = trackFraction,
                            centerValue = trackPrimary,
                            centerUnit = trackSub,
                            totalTicks = 25,
                            majorTickEvery = 5,
                            scaleLabels = remember {
                                listOf(
                                    0f to "50",
                                    36f to "5",
                                    72f to "10",
                                    108f to "15",
                                    144f to "20",
                                    180f to "25",
                                    216f to "30",
                                    252f to "35",
                                    288f to "40",
                                    324f to "45"
                                )
                            },
                            onFractionChange = { newFraction ->
                                val tracks = (newFraction * SleepTimerManager.MAX_TRACK_COUNT)
                                    .roundToInt()
                                    .coerceIn(1, SleepTimerManager.MAX_TRACK_COUNT)
                                if (tracks != selectedDialTracks) {
                                    HapticUtils.performTickHaptic(context)
                                }
                                userEditedTrackDial = true
                                selectedDialTracks = tracks
                                errorMessage = null
                            },
                            modifier = Modifier
                                .size(226.dp)
                                .testTag("sleep_timer_track_dial")
                        )

                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = BentoTextSecondary,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.2.sp
                                    )
                                ) {
                                    append("STOPS AFTER  ")
                                }
                                withStyle(
                                    SpanStyle(
                                        color = BentoPrimary,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.6.sp
                                    )
                                ) {
                                    if (showingLiveTracks && timerState.elapsedSongs > 0) {
                                        append("TRACK ${timerState.elapsedSongs} OF ${timerState.totalSongs}")
                                    } else {
                                        val count = effectiveTracks.coerceAtLeast(1)
                                        append("$count ${if (count == 1) "TRACK" else "TRACKS"}")
                                    }
                                }
                            },
                            fontSize = 12.5.sp,
                            modifier = Modifier.testTag("sleep_timer_tracks_caption")
                        )

                        Button(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                errorMessage = null
                                onStartSongCount(selectedDialTracks)
                                onDismiss()
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BentoPrimary,
                                contentColor = BentoOnPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 11.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .testTag("sleep_timer_track_dial_start_button")
                        ) {
                            val noun = if (selectedDialTracks == 1) "Track" else "Tracks"
                            Text(
                                text = if (timerState.isActive && timerState.mode == "SONGS") {
                                    "Update to $selectedDialTracks $noun"
                                } else {
                                    "Stop After $selectedDialTracks $noun"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Track Count Presets (2 clean rows of 3 pill chips)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Quick Presets",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextSecondary
                        )
                        predefinedSongCounts.chunked(3).forEach { rowSongs ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowSongs.forEach { count ->
                                    SleepPresetChip(
                                        label = "$count ${if (count == 1) "track" else "tracks"}",
                                        selected = selectedDialTracks == count,
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            selectedDialTracks = count
                                            userEditedTrackDial = true
                                            onStartSongCount(count)
                                            onDismiss()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("sleep_timer_preset_track_$count")
                                    )
                                }
                            }
                        }
                    }

                    // Custom Track Count Input Row
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Custom Track Count (1–50)",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customSongs,
                                onValueChange = { raw ->
                                    customSongs = raw.filter { it.isDigit() }.take(3)
                                    errorMessage = null
                                },
                                label = { Text("Tracks (1–50)", fontSize = 11.5.sp) },
                                placeholder = { Text("5") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val count = customSongs.toIntOrNull() ?: 0
                                    when {
                                        count <= 0 -> {
                                            errorMessage = "Enter at least 1 track."
                                        }
                                        count > SleepTimerManager.MAX_TRACK_COUNT -> {
                                            errorMessage = "Maximum track sleep timer limit is 50 tracks."
                                        }
                                        else -> {
                                            HapticUtils.performClickHaptic(context)
                                            errorMessage = null
                                            selectedDialTracks = count
                                            onStartSongCount(count)
                                            onDismiss()
                                        }
                                    }
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BentoPrimary,
                                    contentColor = BentoOnPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("sleep_timer_custom_track_set_button")
                            ) {
                                Text("Set", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                errorMessage?.let { err ->
                    Text(
                        text = err,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = BentoAccentPinkText,
                        modifier = Modifier.testTag("sleep_timer_error_text")
                    )
                }

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (timerState.isActive) {
                        OutlinedButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onCancelTimer()
                                onDismiss()
                            },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, BentoAccentPinkText.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp)
                        ) {
                            Text("Stop Timer", color = BentoAccentPinkText, fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDismiss()
                        },
                        shape = CircleShape,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 46.dp)
                    ) {
                        Text("Close", color = BentoTextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveSleepTimerExtensionCard(
    timerState: SleepTimerState,
    onAddMinutes: (Int) -> Unit,
    onAddSongs: (Int) -> Unit
) {
    val statusText = if (timerState.mode == "TIME") {
        if (timerState.isWaitingForCurrentTrackToEnd || timerState.remainingSeconds <= 0L) {
            "Finishing last track before pausing"
        } else {
            val formattedTime = formatCountdownClock(timerState.remainingSeconds)
            "$formattedTime remaining${if (timerState.finishLastSong) " • finish last track" else ""}"
        }
    } else {
        if (timerState.isFinalSleepTrack) {
            "Final track (${timerState.elapsedSongs}/${timerState.totalSongs}) • pausing next"
        } else if (timerState.elapsedSongs > 0) {
            "Track ${timerState.elapsedSongs} of ${timerState.totalSongs} (${timerState.remainingSongs} left)"
        } else {
            "${timerState.remainingSongs} ${if (timerState.remainingSongs == 1) "track" else "tracks"} queued before pause"
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = BentoPrimary.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.30f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = statusText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoPrimary
            )

            if (timerState.mode == "TIME") {
                val atMaxPendingTime = timerState.remainingSeconds >= SleepTimerManager.MAX_TIME_SECONDS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Extend timer",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (atMaxPendingTime) "Max 3h reached" else "Up to 3h",
                        fontSize = 11.sp,
                        color = if (atMaxPendingTime) BentoAccentPinkText else BentoTextSecondary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 10, 15, 30).forEach { extraMins ->
                        val wouldExceed = (timerState.remainingSeconds + extraMins * 60L) > SleepTimerManager.MAX_TIME_SECONDS
                        val enabled = !wouldExceed
                        Surface(
                            onClick = { onAddMinutes(extraMins) },
                            enabled = enabled,
                            shape = CircleShape,
                            color = if (enabled) BentoCardSurface else BentoCardSurface.copy(alpha = 0.45f),
                            border = BorderStroke(
                                1.dp,
                                if (enabled) BentoPrimary.copy(alpha = 0.4f) else BentoBorder.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 36.dp)
                                .testTag("sleep_timer_add_min_$extraMins")
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 7.dp)) {
                                Text(
                                    text = "+${extraMins}m",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (enabled) BentoPrimary else BentoTextSecondary.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
            } else {
                val atMaxPendingSongs = timerState.remainingSongs >= SleepTimerManager.MAX_TRACK_COUNT
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add tracks",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (atMaxPendingSongs) "Max 50 tracks reached" else "Up to 50 tracks",
                        fontSize = 11.sp,
                        color = if (atMaxPendingSongs) BentoAccentPinkText else BentoTextSecondary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 2, 5, 10).forEach { extraSongs ->
                        val wouldExceed = (timerState.remainingSongs + extraSongs) > SleepTimerManager.MAX_TRACK_COUNT
                        val enabled = !wouldExceed
                        Surface(
                            onClick = { onAddSongs(extraSongs) },
                            enabled = enabled,
                            shape = CircleShape,
                            color = if (enabled) BentoCardSurface else BentoCardSurface.copy(alpha = 0.45f),
                            border = BorderStroke(
                                1.dp,
                                if (enabled) BentoPrimary.copy(alpha = 0.4f) else BentoBorder.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 36.dp)
                                .testTag("sleep_timer_add_song_$extraSongs")
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 7.dp)) {
                                Text(
                                    text = "+$extraSongs",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (enabled) BentoPrimary else BentoTextSecondary.copy(alpha = 0.45f)
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
private fun SleepPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) BentoPrimary.copy(alpha = 0.22f) else BentoCardPurple.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            if (selected) BentoPrimary else BentoBorder.copy(alpha = 0.4f)
        ),
        modifier = modifier.defaultMinSize(minHeight = 38.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) BentoPrimary else BentoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Interactive radial sleep timer clock dial inspired by a tactile circular timer:
 * - Outer track ring with perimeter tick marks and radial scale labels.
 * - Sweeping rounded accent arc from 12 o'clock clockwise to a draggable circular knob with a center dot.
 * - Supports both drag and direct tap anywhere along the dial face.
 * - Respects the app's dynamic / custom theme colors seamlessly.
 */
@Composable
private fun RadialSleepDial(
    fraction: Float,
    centerValue: String,
    centerUnit: String,
    totalTicks: Int,
    majorTickEvery: Int,
    scaleLabels: List<Pair<Float, String>>,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = BentoPrimary
    val surfaceColor = BentoCardSurface
    val trackBgColor = BentoCardPurple.copy(alpha = 0.52f)
    val tickInactiveColor = BentoTextSecondary.copy(alpha = 0.34f)
    val majorTickColor = BentoTextSecondary.copy(alpha = 0.65f)
    val labelTextColor = BentoTextSecondary.copy(alpha = 0.78f)
    val density = LocalDensity.current
    val labelTextSizePx = with(density) { 10.sp.toPx() }

    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120),
        label = "radial_sleep_dial_fraction"
    )

    val labelPaint = remember(labelTextColor, labelTextSizePx) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = labelTextColor.toArgb()
            textSize = labelTextSizePx
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }

    val latestOnChange by rememberUpdatedState(onFractionChange)

    fun offsetToFraction(pos: Offset, size: Size): Float {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dx = pos.x - cx
        val dy = pos.y - cy
        // 12 o'clock = 0 deg, clockwise increasing to 360 deg
        val angleDeg = ((atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI) + 90.0 + 360.0) % 360.0
        val raw = (angleDeg / 360.0).toFloat()
        return if (raw <= 0.005f) 1.0f else raw.coerceIn(0.005f, 1.0f)
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val sz = Size(size.width.toFloat(), size.height.toFloat())
                    latestOnChange(offsetToFraction(offset, sz))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val sz = Size(size.width.toFloat(), size.height.toFloat())
                    latestOnChange(offsetToFraction(change.position, sz))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDim = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val strokeWidth = minDim * 0.058f
            val outerRingRadius = (minDim / 2f) - strokeWidth * 1.45f

            // Subtle recessed dial backdrop disc
            drawCircle(
                color = trackBgColor.copy(alpha = 0.28f),
                radius = outerRingRadius + strokeWidth * 0.52f,
                center = center
            )

            // Full perimeter track ring
            drawCircle(
                color = trackBgColor,
                radius = outerRingRadius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            // Crisp perimeter tick marks
            val tickOuter = outerRingRadius + strokeWidth * 0.14f
            for (i in 0 until totalTicks) {
                val deg = (i.toFloat() / totalTicks.toFloat()) * 360f - 90f
                val rad = deg * PI.toFloat() / 180f
                val isMajor = (i % majorTickEvery == 0)
                val tickLen = if (isMajor) strokeWidth * 0.92f else strokeWidth * 0.52f
                val tickInner = tickOuter - tickLen
                val start = Offset(center.x + tickInner * cos(rad), center.y + tickInner * sin(rad))
                val end = Offset(center.x + tickOuter * cos(rad), center.y + tickOuter * sin(rad))
                drawLine(
                    color = if (isMajor) majorTickColor else tickInactiveColor,
                    start = start,
                    end = end,
                    strokeWidth = if (isMajor) 2.2.dp.toPx() else 1.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Inner radial scale labels
            val labelRadius = outerRingRadius - strokeWidth * 1.65f
            val fontMetrics = labelPaint.fontMetrics
            val baselineAdjust = -(fontMetrics.ascent + fontMetrics.descent) / 2f
            drawContext.canvas.nativeCanvas.let { native ->
                scaleLabels.forEach { (angleFromTopDeg, text) ->
                    val rad = (angleFromTopDeg - 90f) * PI.toFloat() / 180f
                    val lx = center.x + labelRadius * cos(rad)
                    val ly = center.y + labelRadius * sin(rad) + baselineAdjust
                    native.drawText(text, lx, ly, labelPaint)
                }
            }

            // Sweeping glowing accent arc (clockwise from 12 o'clock)
            val sweepAngle = (animatedFraction * 360f).coerceIn(2f, 359.8f)
            val arcTopLeft = Offset(center.x - outerRingRadius, center.y - outerRingRadius)
            val arcSize = Size(outerRingRadius * 2f, outerRingRadius * 2f)

            drawArc(
                color = accentColor.copy(alpha = 0.20f),
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth * 1.42f, cap = StrokeCap.Round)
            )
            drawArc(
                color = accentColor,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Circular draggable handle knob with crisp center dot
            val handleRad = (sweepAngle - 90f) * PI.toFloat() / 180f
            val handleCenter = Offset(
                center.x + outerRingRadius * cos(handleRad),
                center.y + outerRingRadius * sin(handleRad)
            )
            val handleRadius = strokeWidth * 1.12f

            // Outer cutout halo so the knob sits cleanly above ticks/arc
            drawCircle(
                color = surfaceColor,
                radius = handleRadius + 2.5.dp.toPx(),
                center = handleCenter
            )
            // Accent knob body
            drawCircle(
                color = accentColor,
                radius = handleRadius,
                center = handleCenter
            )
            // Center dark dot matching the reference aesthetic
            drawCircle(
                color = surfaceColor,
                radius = handleRadius * 0.28f,
                center = handleCenter
            )
        }

        // Center Duration Readout
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = centerValue,
                fontSize = if (centerValue.length <= 3) 40.sp else if (centerValue.length <= 5) 32.sp else 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = BentoTextPrimary,
                letterSpacing = (-0.8).sp,
                maxLines = 1
            )
            Text(
                text = centerUnit,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoPrimary,
                maxLines = 1
            )
        }
    }
}

private fun formatDialMinutesDisplay(totalMinutes: Int): Pair<String, String> {
    val safe = totalMinutes.coerceIn(1, SleepTimerManager.MAX_TIME_MINUTES)
    return if (safe < 60) {
        "$safe" to "min"
    } else {
        val h = safe / 60
        val m = safe % 60
        if (m == 0) {
            "$h" to if (h == 1) "hour" else "hours"
        } else {
            "${h}h ${m}m" to "duration"
        }
    }
}

private fun formatShortMinuteChip(mins: Int): String = when {
    mins >= 60 && mins % 60 == 0 -> "${mins / 60}h"
    mins > 60 -> "${mins / 60}h ${mins % 60}m"
    else -> "${mins}m"
}

private fun formatCountdownClock(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hrs = safe / 3600L
    val mins = (safe % 3600L) / 60L
    val secs = safe % 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%d:%02d", mins, secs)
    }
}