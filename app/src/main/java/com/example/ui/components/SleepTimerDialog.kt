package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.SleepTimerManager
import com.example.service.SleepTimerState
import com.example.ui.theme.*
import com.example.util.HapticUtils

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

    val predefinedMinutes = listOf(5, 10, 15, 30, 45, 60, 90, 120, 180)
    val predefinedSongCounts = listOf(1, 2, 5, 10, 25, 50)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
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
                                .size(42.dp)
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
                                text = if (timerState.isActive) "Timer currently running" else "Auto-pause playback after time or tracks",
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
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close sleep timer dialog",
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Active Status Banner + Quick Extend Controls
                if (timerState.isActive) {
                    val statusText = if (timerState.mode == "TIME") {
                        if (timerState.isWaitingForCurrentTrackToEnd || timerState.remainingSeconds <= 0L) {
                            "Finishing current track before pausing on next track"
                        } else {
                            val mins = timerState.remainingSeconds / 60
                            val secs = timerState.remainingSeconds % 60
                            val formattedTime = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                            "$formattedTime remaining${if (timerState.finishLastSong) " • finish current track" else ""}"
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
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = statusText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }

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
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                errorMessage = null
                                                onAddMinutes(extraMins)
                                            },
                                            enabled = enabled,
                                            shape = CircleShape,
                                            color = if (enabled) BentoCardSurface else BentoCardSurface.copy(alpha = 0.45f),
                                            border = BorderStroke(1.dp, if (enabled) BentoPrimary.copy(alpha = 0.4f) else BentoBorder.copy(alpha = 0.3f)),
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
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                errorMessage = null
                                                onAddSongs(extraSongs)
                                            },
                                            enabled = enabled,
                                            shape = CircleShape,
                                            color = if (enabled) BentoCardSurface else BentoCardSurface.copy(alpha = 0.45f),
                                            border = BorderStroke(1.dp, if (enabled) BentoPrimary.copy(alpha = 0.4f) else BentoBorder.copy(alpha = 0.3f)),
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

                // Sleek Pill Mode Selector (By Time vs By Tracks)
                Surface(
                    shape = CircleShape,
                    color = BentoCardPurple.copy(alpha = 0.6f),
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

                if (selectedTab == 0) {
                    // Time Presets (3 clean rows of 3 pill chips)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Quick Duration",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextSecondary
                        )
                        predefinedMinutes.chunked(3).forEach { rowMins ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowMins.forEach { mins ->
                                    val label = when {
                                        mins >= 60 && mins % 60 == 0 -> "${mins / 60} hr${if (mins > 60) "s" else ""}"
                                        mins == 90 -> "1h 30m"
                                        else -> "$mins min"
                                    }
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onStartTimerMinutes(mins, finishLastSongTime)
                                            onDismiss()
                                        },
                                        shape = CircleShape,
                                        color = BentoCardPurple.copy(alpha = 0.55f),
                                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                            Text(
                                                text = label,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = BentoTextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Custom Time Input Row
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Custom Duration (up to 3h)",
                            fontSize = 12.sp,
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

                    // Finish Last Song Switch Row
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = BentoCardPurple.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Finish current track",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Wait for the playing song to finish before pausing",
                                    fontSize = 11.5.sp,
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
                                    checkedTrackColor = BentoPrimary
                                )
                            )
                        }
                    }
                } else {
                    // Track Count Presets (2 clean rows of 3 pill chips)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Stop After Tracks",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextSecondary
                        )
                        predefinedSongCounts.chunked(3).forEach { rowSongs ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowSongs.forEach { count ->
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onStartSongCount(count)
                                            onDismiss()
                                        },
                                        shape = CircleShape,
                                        color = BentoCardPurple.copy(alpha = 0.55f),
                                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                            Text(
                                                text = "$count ${if (count == 1) "track" else "tracks"}",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = BentoTextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Custom Track Count Input Row
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Custom Track Count (1–50)",
                            fontSize = 12.sp,
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