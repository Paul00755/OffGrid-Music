package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.SleepTimerManager
import com.example.service.SleepTimerState
import com.example.ui.theme.*

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
    var selectedTab by remember { mutableIntStateOf(if (timerState.isActive && timerState.mode == "SONGS") 1 else 0) }
    var customHours by remember { mutableStateOf("") }
    var customMinutes by remember { mutableStateOf("") }
    var customSongs by remember { mutableStateOf("") }
    var finishLastSongTime by remember(timerState.finishLastSong) { mutableStateOf(timerState.finishLastSong) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val predefinedMinutes = listOf(5, 10, 15, 30, 45, 60, 90, 120, 180)
    val predefinedSongCounts = listOf(1, 2, 5, 10, 25, 50)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Sleep Timer",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
                if (timerState.isActive) {
                    val statusText = if (timerState.mode == "TIME") {
                        if (timerState.isWaitingForCurrentTrackToEnd || timerState.remainingSeconds <= 0L) {
                            "Active: Finishing current track before stopping on next track at 0:00"
                        } else {
                            val mins = timerState.remainingSeconds / 60
                            val secs = timerState.remainingSeconds % 60
                            val formattedTime = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                            "Active: $formattedTime remaining${if (timerState.finishLastSong) " (finish last track)" else ""}"
                        }
                    } else {
                        if (timerState.isFinalSleepTrack) {
                            "Active: Final track (${timerState.elapsedSongs}/${timerState.totalSongs}) - stopping on next track at 0:00"
                        } else if (timerState.elapsedSongs > 0) {
                            "Active: Track ${timerState.elapsedSongs} of ${timerState.totalSongs} (${timerState.remainingSongs} pending)"
                        } else {
                            "Active: ${timerState.remainingSongs} ${if (timerState.remainingSongs == 1) "track" else "tracks"} pending (starts from next song)"
                        }
                    }
                    Text(
                        text = statusText,
                        fontSize = 12.5.sp,
                        color = BentoPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Active Quick Extension Row (TIME mode — strictly bounded by 180 mins pending)
                if (timerState.isActive && timerState.mode == "TIME") {
                    val atMaxPendingTime = timerState.remainingSeconds >= SleepTimerManager.MAX_TIME_SECONDS
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BentoCardDarkPurple),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Add More Time:",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextSecondary
                                )
                                Text(
                                    text = if (atMaxPendingTime) "Max 3h pending reached" else "Max 3h (180m) pending",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (atMaxPendingTime) BentoAccentPinkText else BentoTextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(5 to "+5m", 10 to "+10m", 15 to "+15m", 30 to "+30m").forEach { (extraMins, label) ->
                                    val canAdd = SleepTimerManager.canAddMinutes(timerState, extraMins)
                                    Button(
                                        onClick = {
                                            if (canAdd) {
                                                errorMessage = null
                                                onAddMinutes(extraMins)
                                            }
                                        },
                                        enabled = canAdd,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BentoPrimary.copy(alpha = 0.25f),
                                            disabledContainerColor = BentoBorder.copy(alpha = 0.22f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .testTag("sleep_timer_add_min_$extraMins")
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (canAdd) BentoPrimary else BentoTextSecondary.copy(alpha = 0.45f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Active Quick Extension Row (SONGS mode — strictly bounded by 50 tracks pending)
                if (timerState.isActive && timerState.mode == "SONGS") {
                    val atMaxPendingSongs = timerState.remainingSongs >= SleepTimerManager.MAX_TRACK_COUNT
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BentoCardDarkPurple),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Add More Tracks:",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextSecondary
                                )
                                Text(
                                    text = if (atMaxPendingSongs) "Max 50 pending reached" else "Max 50 tracks pending",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (atMaxPendingSongs) BentoAccentPinkText else BentoTextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(1 to "+1 track", 2 to "+2 tracks", 5 to "+5 tracks").forEach { (extraSongs, label) ->
                                    val canAdd = SleepTimerManager.canAddSongs(timerState, extraSongs)
                                    Button(
                                        onClick = {
                                            if (canAdd) {
                                                errorMessage = null
                                                onAddSongs(extraSongs)
                                            }
                                        },
                                        enabled = canAdd,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BentoPrimary.copy(alpha = 0.25f),
                                            disabledContainerColor = BentoBorder.copy(alpha = 0.22f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .testTag("sleep_timer_add_song_$extraSongs")
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (canAdd) BentoPrimary else BentoTextSecondary.copy(alpha = 0.45f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Tab Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BentoCardDarkPurple)
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selectedTab == 0) BentoPrimary else Color.Transparent)
                            .clickable {
                                selectedTab = 0
                                errorMessage = null
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                tint = if (selectedTab == 0) BentoOnPrimary else BentoTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Duration",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == 0) BentoOnPrimary else BentoTextSecondary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selectedTab == 1) BentoPrimary else Color.Transparent)
                            .clickable {
                                selectedTab = 1
                                errorMessage = null
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (selectedTab == 1) BentoOnPrimary else BentoTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Track Count",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == 1) BentoOnPrimary else BentoTextSecondary
                            )
                        }
                    }
                }

                if (selectedTab == 0) {
                    // Time-based options
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Select Duration (max 3h / 180 mins):", fontSize = 12.5.sp, color = BentoTextSecondary)

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.height(130.dp)
                        ) {
                            items(predefinedMinutes) { mins ->
                                val isSelected = timerState.isActive && timerState.mode == "TIME" && timerState.initialMinutes == mins
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) BentoPrimary else BentoCardDarkPurple)
                                        .clickable {
                                            onStartTimerMinutes(mins, finishLastSongTime)
                                            onDismiss()
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (mins >= 60) "${mins / 60}h ${mins % 60}m".replace(" 0m", "") else "$mins mins",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isSelected) BentoOnPrimary else BentoTextPrimary
                                    )
                                }
                            }
                        }

                        Text("Custom Time (max 3h / 180m):", fontSize = 12.5.sp, color = BentoTextSecondary)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customHours,
                                onValueChange = {
                                    customHours = it.filter { char -> char.isDigit() }.take(2)
                                    errorMessage = null
                                },
                                label = { Text("Hours") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = customMinutes,
                                onValueChange = {
                                    customMinutes = it.filter { char -> char.isDigit() }.take(3)
                                    errorMessage = null
                                },
                                label = { Text("Mins") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    val hrs = customHours.toIntOrNull() ?: 0
                                    val mins = customMinutes.toIntOrNull() ?: 0
                                    val total = (hrs * 60) + mins
                                    when {
                                        total <= 0 -> errorMessage = "Duration must be > 0 mins"
                                        total > SleepTimerManager.MAX_TIME_MINUTES -> {
                                            errorMessage = "Maximum sleep timer duration is 3 hours (${SleepTimerManager.MAX_TIME_MINUTES} mins)"
                                        }
                                        else -> {
                                            errorMessage = null
                                            onStartTimerMinutes(total, finishLastSongTime)
                                            onDismiss()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                                modifier = Modifier.testTag("sleep_timer_custom_time_set_button")
                            ) {
                                Text("Set", color = BentoOnPrimary)
                            }
                        }

                        // Finish Last Track Checkbox (Duration/Time mode)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val updated = !finishLastSongTime
                                    finishLastSongTime = updated
                                    onSetFinishLastSong(updated)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = finishLastSongTime,
                                onCheckedChange = { updated ->
                                    finishLastSongTime = updated
                                    onSetFinishLastSong(updated)
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Finish last track before stopping",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Finishes current song, transitions to next song & stops at 0:00",
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }
                    }
                } else {
                    // Track Count options
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Counts from the next track after current song, then stops on the following track at 0:00 (max 50 tracks):",
                            fontSize = 12.sp,
                            color = BentoTextSecondary
                        )

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.height(90.dp)
                        ) {
                            items(predefinedSongCounts) { count ->
                                val isSelected = timerState.isActive && timerState.mode == "SONGS" && timerState.totalSongs == count
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) BentoPrimary else BentoCardDarkPurple)
                                        .clickable {
                                            onStartSongCount(count)
                                            onDismiss()
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$count ${if (count == 1) "track" else "tracks"}",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isSelected) BentoOnPrimary else BentoTextPrimary
                                    )
                                }
                            }
                        }

                        Text("Custom Track Count (max 50):", fontSize = 12.5.sp, color = BentoTextSecondary)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customSongs,
                                onValueChange = {
                                    customSongs = it.filter { char -> char.isDigit() }.take(3)
                                    errorMessage = null
                                },
                                label = { Text("Tracks (1–50)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    val count = customSongs.toIntOrNull() ?: 0
                                    when {
                                        count <= 0 -> errorMessage = "Track count must be > 0"
                                        count > SleepTimerManager.MAX_TRACK_COUNT -> {
                                            errorMessage = "Maximum track sleep timer is ${SleepTimerManager.MAX_TRACK_COUNT} tracks"
                                        }
                                        else -> {
                                            errorMessage = null
                                            onStartSongCount(count)
                                            onDismiss()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                                modifier = Modifier.testTag("sleep_timer_custom_track_set_button")
                            ) {
                                Text("Set", color = BentoOnPrimary)
                            }
                        }
                    }
                }

                errorMessage?.let { err ->
                    Text(
                        text = err,
                        color = BentoAccentPinkText,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("sleep_timer_error_text")
                    )
                }
            }
        },
        confirmButton = {
            if (timerState.isActive) {
                TextButton(
                    onClick = {
                        onCancelTimer()
                        onDismiss()
                    }
                ) {
                    Text("Turn Off Timer", color = BentoAccentPinkText, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = BentoTextSecondary)
            }
        },
        containerColor = BentoCardSurface
    )
}