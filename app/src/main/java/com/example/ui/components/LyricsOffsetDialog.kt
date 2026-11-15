package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.ui.screens.cleanLyricDisplayText
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

private fun formatOffsetClock(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val totalSec = clamped / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    val tenths = (clamped % 1000L) / 100L
    return "%02d:%02d.%d".format(min, sec, tenths)
}

@Composable
fun LyricsOffsetDialog(
    song: Song,
    currentOffsetMs: Long,
    onOffsetChanged: (Long) -> Unit,
    onDismiss: () -> Unit,
    syncedLines: List<Pair<Long, String>> = emptyList(),
    positionMs: Long = 0L,
    playbackPositionFlow: StateFlow<Long>? = null
) {
    val context = LocalContext.current
    var offsetMs by remember(song.id) { mutableLongStateOf(currentOffsetMs) }
    val livePosState = playbackPositionFlow?.collectAsStateWithLifecycle(initialValue = positionMs)

    fun applyOffset(newOffset: Long, playHaptic: Boolean = true) {
        val clamped = newOffset.coerceIn(-15000L, 15000L)
        if (clamped != offsetMs || playHaptic) {
            val changed = clamped != offsetMs
            offsetMs = clamped
            if (playHaptic || changed) {
                HapticUtils.performClickHaptic(context)
            }
            onOffsetChanged(clamped)
        }
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (isDark) Color(0xFF14141E) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(BentoPrimary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lyrics Sync Offset",
                            color = BentoTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = song.displayTitle,
                            color = BentoTextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = BentoTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Offset Readout Meter Box
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isDark) Color(0xFF1E1E2C) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(
                        1.dp,
                        if (offsetMs != 0L) BentoPrimary.copy(alpha = 0.6f) else (if (isDark) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val seconds = offsetMs / 1000.0
                        val formattedOffset = if (offsetMs == 0L) {
                            "0.00 s"
                        } else if (offsetMs > 0) {
                            "+${"%.2f".format(seconds)} s"
                        } else {
                            "${"%.2f".format(seconds)} s"
                        }

                        Text(
                            text = formattedOffset,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (offsetMs != 0L) BentoPrimary else BentoTextPrimary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        val explanationText = when {
                            offsetMs > 0 -> "Advance: lyrics appear ${"%.2f".format(seconds)}s earlier"
                            offsetMs < 0 -> "Delay: lyrics appear ${"%.2f".format(abs(seconds))}s later"
                            else -> "Original timing (no offset applied)"
                        }

                        Text(
                            text = explanationText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (offsetMs != 0L) BentoPrimary.copy(alpha = 0.9f) else BentoTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Live Lyric Preview (shows track position, effective lyric position, and active line)
                if (syncedLines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val rawPos = livePosState?.value ?: positionMs
                    val effectivePos = rawPos + offsetMs
                    val isBeforeFirst = effectivePos < syncedLines[0].first
                    val previewIdx = if (isBeforeFirst) -1 else Lyrics.findActiveLineIndex(syncedLines, effectivePos)
                    val activeText = if (previewIdx in syncedLines.indices) {
                        cleanLyricDisplayText(syncedLines[previewIdx].second)
                    } else {
                        "Intro • First line at ${formatOffsetClock(syncedLines[0].first)}"
                    }
                    val nextText = if (previewIdx + 1 in syncedLines.indices) {
                        cleanLyricDisplayText(syncedLines[previewIdx + 1].second)
                    } else null

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isDark) Color(0xFF181824) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Track ${formatOffsetClock(rawPos)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = BentoTextSecondary
                                )
                                Text(
                                    text = "Lyrics ${if (effectivePos < 0L) "-${formatOffsetClock(-effectivePos)}" else formatOffsetClock(effectivePos)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoPrimary
                                )
                            }
                            Text(
                                text = activeText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (previewIdx >= 0) BentoTextPrimary else BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (nextText != null) {
                                Text(
                                    text = "Next: $nextText",
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Continuous Slider (-15s to +15s)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("-15s (Delay)", fontSize = 11.sp, color = BentoTextSecondary)
                        Text("0s", fontSize = 11.sp, color = BentoTextSecondary)
                        Text("+15s (Advance)", fontSize = 11.sp, color = BentoTextSecondary)
                    }
                    Slider(
                        value = offsetMs.toFloat().coerceIn(-15000f, 15000f),
                        onValueChange = { newVal ->
                            val rounded = (kotlin.math.round(newVal / 50f) * 50).toLong()
                            if (rounded != offsetMs) {
                                applyOffset(rounded, playHaptic = false)
                            }
                        },
                        valueRange = -15000f..15000f,
                        colors = SliderDefaults.colors(
                            thumbColor = BentoPrimary,
                            activeTrackColor = BentoPrimary,
                            inactiveTrackColor = if (isDark) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("lyrics_offset_slider")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                val buttonBg = if (isDark) Color(0xFF282838) else MaterialTheme.colorScheme.surfaceVariant
                val buttonSecondaryBg = if (isDark) Color(0xFF232332) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                val buttonTextColor = BentoTextPrimary

                // Quick Increment Steppers Row 1 (Fine tuning: ±0.1s, ±0.5s)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs - 500L) },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("-0.5s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs - 100L) },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("-0.1s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs + 100L) },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("+0.1s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs + 500L) },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("+0.5s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Quick Increment Steppers Row 2 (Coarse tuning: ±1.0s, ±2.0s & Reset)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs - 2000L) },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonSecondaryBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp)
                    ) {
                        Text("-2s", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs - 1000L) },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonSecondaryBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp)
                    ) {
                        Text("-1s", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalButton(
                        onClick = { applyOffset(0L) },
                        modifier = Modifier.weight(1.3f).defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (offsetMs != 0L) BentoPrimary.copy(alpha = 0.2f) else buttonSecondaryBg,
                            contentColor = if (offsetMs != 0L) BentoPrimary else buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Reset", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs + 1000L) },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonSecondaryBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp)
                    ) {
                        Text("+1s", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs + 2000L) },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonSecondaryBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp)
                    ) {
                        Text("+2s", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Tip: Use negative values (Delay) if lyrics show before the vocals; positive (Advance) if lyrics fall behind.",
                    fontSize = 11.sp,
                    color = BentoTextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text("Done", color = BentoOnPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    )
}
