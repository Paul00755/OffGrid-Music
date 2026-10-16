package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlin.math.abs

@Composable
fun LyricsOffsetDialog(
    song: Song,
    currentOffsetMs: Long,
    onOffsetChanged: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var offsetMs by remember(currentOffsetMs) { mutableLongStateOf(currentOffsetMs) }

    fun applyOffset(newOffset: Long) {
        val clamped = newOffset.coerceIn(-15000L, 15000L)
        offsetMs = clamped
        HapticUtils.performClickHaptic(context)
        onOffsetChanged(clamped)
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                    Column {
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
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
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
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (offsetMs != 0L) BentoPrimary.copy(alpha = 0.6f) else (if (isDark) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp, horizontal = 12.dp),
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
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (offsetMs != 0L) BentoPrimary else BentoTextPrimary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        val explanationText = when {
                            offsetMs > 0 -> "Lyrics appear ${"%.2f".format(seconds)}s earlier (ahead)"
                            offsetMs < 0 -> "Lyrics appear ${"%.2f".format(abs(seconds))}s later (delayed)"
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

                Spacer(modifier = Modifier.height(16.dp))

                // Continuous Slider (-10s to +10s)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("-10s (Delay)", fontSize = 11.sp, color = BentoTextSecondary)
                        Text("0s", fontSize = 11.sp, color = BentoTextSecondary)
                        Text("+10s (Advance)", fontSize = 11.sp, color = BentoTextSecondary)
                    }
                    Slider(
                        value = offsetMs.toFloat(),
                        onValueChange = { newVal ->
                            // Snap to nearest 50ms
                            val rounded = (kotlin.math.round(newVal / 50f) * 50).toLong()
                            applyOffset(rounded)
                        },
                        valueRange = -10000f..10000f,
                        colors = SliderDefaults.colors(
                            thumbColor = BentoPrimary,
                            activeTrackColor = BentoPrimary,
                            inactiveTrackColor = if (isDark) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("lyrics_offset_slider")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

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
                        modifier = Modifier.weight(1f),
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
                        modifier = Modifier.weight(1f),
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
                        modifier = Modifier.weight(1f),
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
                        modifier = Modifier.weight(1f),
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

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Increment Steppers Row 2 (Coarse tuning: ±1.0s, ±2.0s)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs - 1000L) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonSecondaryBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("-1.0s", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalButton(
                        onClick = { applyOffset(0L) },
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (offsetMs != 0L) BentoPrimary.copy(alpha = 0.2f) else buttonSecondaryBg,
                            contentColor = if (offsetMs != 0L) BentoPrimary else buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { applyOffset(offsetMs + 1000L) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = buttonSecondaryBg,
                            contentColor = buttonTextColor
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("+1.0s", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Offset shifts lyrics timing for this track. Saved and remembered across app restart.",
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
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text("Done", color = BentoOnPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    )
}
