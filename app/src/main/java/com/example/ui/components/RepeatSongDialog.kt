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
import androidx.compose.material.icons.filled.RepeatOne
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.ui.theme.*

const val MAX_SONG_REPEAT_COUNT = 10

@Composable
fun RepeatSongDialog(
    song: Song,
    currentRepeatCount: Int,
    isTrackSleepTimerActive: Boolean = false,
    onSetRepeatCount: (Int) -> Unit,
    onClearRepeat: () -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember(song.id, currentRepeatCount) {
        mutableStateOf(if (currentRepeatCount > 0) currentRepeatCount.coerceAtMost(MAX_SONG_REPEAT_COUNT).toString() else "")
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val quickCounts = (1..MAX_SONG_REPEAT_COUNT).toList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BentoPrimary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.RepeatOne,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Repeat this song",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "${song.displayTitle} • ${song.displayArtist}",
                        fontSize = 12.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (currentRepeatCount > 0) {
                    Surface(
                        color = BentoPrimary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Active: Repeating $currentRepeatCount more ${if (currentRepeatCount == 1) "time" else "times"}",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                if (isTrackSleepTimerActive) {
                    Surface(
                        color = BentoCardDarkPurple,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Track sleep timer is active: each repeat counts as 1 track.",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }
                }

                Text(
                    text = "Enter how many times to repeat (1 to $MAX_SONG_REPEAT_COUNT):",
                    fontSize = 13.sp,
                    color = BentoTextSecondary
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(2)
                        inputText = digits
                        val parsed = digits.toIntOrNull()
                        errorMessage = when {
                            digits.isEmpty() -> null
                            parsed == null || parsed < 1 -> "Enter a count between 1 and $MAX_SONG_REPEAT_COUNT"
                            parsed > MAX_SONG_REPEAT_COUNT -> "Maximum repeat limit is $MAX_SONG_REPEAT_COUNT times"
                            else -> null
                        }
                    },
                    label = { Text("Repeat count (max $MAX_SONG_REPEAT_COUNT)") },
                    placeholder = { Text("1 – $MAX_SONG_REPEAT_COUNT") },
                    isError = errorMessage != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("repeat_count_input")
                )

                Text(
                    text = "Quick Select:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = BentoTextSecondary
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(84.dp)
                ) {
                    items(quickCounts) { count ->
                        val isSelected = inputText.toIntOrNull() == count
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) BentoPrimary else BentoCardDarkPurple)
                                .clickable {
                                    inputText = count.toString()
                                    errorMessage = null
                                }
                                .padding(vertical = 9.dp)
                                .testTag("repeat_count_chip_$count"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${count}×",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) BentoOnPrimary else BentoTextPrimary
                            )
                        }
                    }
                }

                errorMessage?.let { err ->
                    Text(
                        text = err,
                        color = BentoAccentPinkText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentRepeatCount > 0) {
                    TextButton(
                        onClick = {
                            onClearRepeat()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("repeat_count_clear_button")
                    ) {
                        Text("Turn Off", color = BentoAccentPinkText, fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = {
                        val parsed = inputText.toIntOrNull()
                        when {
                            parsed == null || parsed < 1 -> {
                                errorMessage = "Enter a number from 1 to $MAX_SONG_REPEAT_COUNT"
                            }
                            parsed > MAX_SONG_REPEAT_COUNT -> {
                                errorMessage = "Maximum repeat limit is $MAX_SONG_REPEAT_COUNT times"
                            }
                            else -> {
                                errorMessage = null
                                onSetRepeatCount(parsed.coerceIn(1, MAX_SONG_REPEAT_COUNT))
                                onDismiss()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                    modifier = Modifier.testTag("repeat_count_confirm_button")
                ) {
                    Text("Apply", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("repeat_count_cancel_button")
            ) {
                Text("Cancel", color = BentoTextSecondary)
            }
        },
        containerColor = BentoCardSurface
    )
}
