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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.SleepTimerState
import com.example.ui.theme.*

@Composable
fun SleepTimerDialog(
    timerState: SleepTimerState,
    onStartTimerMinutes: (Int, Boolean) -> Unit,
    onStartSongCount: (Int) -> Unit,
    onAddMinutes: (Int) -> Unit = {},
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

    val predefinedMinutes = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    val predefinedSongCounts = listOf(1, 2, 3, 5, 10)

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
                            "Active: Track ${timerState.elapsedSongs} of ${timerState.totalSongs} (${timerState.remainingSongs} after this)"
                        } else {
                            "Active: Counting ${timerState.totalSongs} ${if (timerState.totalSongs == 1) "track" else "tracks"} starting from next song"
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
                // Active Quick Extension Row (if active in TIME mode)
                if (timerState.isActive && timerState.mode == "TIME") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BentoCardDarkPurple),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Add More Time:",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(5 to "+5m", 15 to "+15m", 30 to "+30m").forEach { (extraMins, label) ->
                                    Button(
                                        onClick = { onAddMinutes(extraMins) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary.copy(alpha = 0.25f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary
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
                            .clickable { selectedTab = 0 }
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
                            .clickable { selectedTab = 1 }
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
                        Text("Select Duration:", fontSize = 12.5.sp, color = BentoTextSecondary)

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

                        Text("Custom Time:", fontSize = 12.5.sp, color = BentoTextSecondary)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customHours,
                                onValueChange = { customHours = it.filter { char -> char.isDigit() } },
                                label = { Text("Hours") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = customMinutes,
                                onValueChange = { customMinutes = it.filter { char -> char.isDigit() } },
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
                                    if (total > 0) {
                                        errorMessage = null
                                        onStartTimerMinutes(total, finishLastSongTime)
                                        onDismiss()
                                    } else {
                                        errorMessage = "Duration must be > 0 mins"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
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
                            "Counts from the next track after current song, then stops on the following track at 0:00:",
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

                        Text("Custom Track Count:", fontSize = 12.5.sp, color = BentoTextSecondary)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customSongs,
                                onValueChange = { customSongs = it.filter { char -> char.isDigit() } },
                                label = { Text("Tracks") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    val count = customSongs.toIntOrNull() ?: 0
                                    if (count > 0) {
                                        errorMessage = null
                                        onStartSongCount(count)
                                        onDismiss()
                                    } else {
                                        errorMessage = "Track count must be > 0"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                            ) {
                                Text("Set", color = BentoOnPrimary)
                            }
                        }
                    }
                }

                errorMessage?.let { err ->
                    Text(err, color = BentoAccentPinkText, fontSize = 12.sp)
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