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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.HapticUtils

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
    val context = LocalContext.current
    var inputText by remember(song.id, currentRepeatCount) {
        mutableStateOf(
            if (currentRepeatCount > 0) currentRepeatCount.coerceAtMost(MAX_SONG_REPEAT_COUNT).toString() else "1"
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val parsedCount = inputText.toIntOrNull()
    val quickCounts = (1..MAX_SONG_REPEAT_COUNT).toList()

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
                .widthIn(max = 440.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            imageVector = Icons.Default.RepeatOne,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Repeat This Song",
                            fontSize = 18.5.sp,
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

                // Active Status Banner
                if (currentRepeatCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BentoPrimary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.30f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Repeating $currentRepeatCount more ${if (currentRepeatCount == 1) "time" else "times"}",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onClearRepeat()
                                    onDismiss()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("repeat_count_clear_button")
                            ) {
                                Text(
                                    text = "Turn Off",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoAccentPinkText
                                )
                            }
                        }
                    }
                }

                if (isTrackSleepTimerActive) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BentoCardPurple.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
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
                                text = "Track sleep timer active: each repeat counts as 1 track.",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }
                }

                // Tactile Stepper + Numeric Input
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = BentoCardPurple.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                val next = ((parsedCount ?: 1) - 1).coerceAtLeast(1)
                                inputText = next.toString()
                                errorMessage = null
                            },
                            enabled = (parsedCount ?: 1) > 1,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = BentoCardSurface,
                                contentColor = BentoTextPrimary
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease repeat count", modifier = Modifier.size(20.dp))
                        }

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
                            singleLine = true,
                            isError = errorMessage != null,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = BentoTextPrimary
                            ),
                            placeholder = {
                                Text(
                                    text = "1–$MAX_SONG_REPEAT_COUNT",
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BentoPrimary,
                                unfocusedBorderColor = BentoBorder.copy(alpha = 0.55f),
                                focusedContainerColor = BentoCardSurface,
                                unfocusedContainerColor = BentoCardSurface
                            ),
                            modifier = Modifier
                                .width(116.dp)
                                .testTag("repeat_count_input")
                        )

                        FilledTonalIconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                val next = ((parsedCount ?: 0) + 1).coerceAtMost(MAX_SONG_REPEAT_COUNT)
                                inputText = next.toString()
                                errorMessage = null
                            },
                            enabled = (parsedCount ?: 0) < MAX_SONG_REPEAT_COUNT,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = BentoCardSurface,
                                contentColor = BentoTextPrimary
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase repeat count", modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Quick Select Pill Grid (2 clean rows of 5)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Quick Select (max ${MAX_SONG_REPEAT_COUNT}×)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextSecondary
                    )
                    quickCounts.chunked(5).forEach { rowCounts ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowCounts.forEach { count ->
                                val isSelected = parsedCount == count
                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        inputText = count.toString()
                                        errorMessage = null
                                    },
                                    shape = CircleShape,
                                    color = if (isSelected) BentoPrimary else BentoCardPurple.copy(alpha = 0.55f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.45f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 40.dp)
                                        .testTag("repeat_count_chip_$count")
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                        Text(
                                            text = "${count}×",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) BentoOnPrimary else BentoTextPrimary
                                        )
                                    }
                                }
                            }
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

                // Footer Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                            .testTag("repeat_count_cancel_button")
                    ) {
                        Text("Cancel", color = BentoTextSecondary, fontWeight = FontWeight.SemiBold)
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
                                    HapticUtils.performClickHaptic(context)
                                    errorMessage = null
                                    onSetRepeatCount(parsed.coerceIn(1, MAX_SONG_REPEAT_COUNT))
                                    onDismiss()
                                }
                            }
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 46.dp)
                            .testTag("repeat_count_confirm_button")
                    ) {
                        Text("Apply", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
