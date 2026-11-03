package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BentoBorder
import com.example.ui.theme.BentoPrimary
import com.example.ui.theme.BentoTextSecondary
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Isolated real-time progress indicator.
 * By deferring [playbackPositionFlow] reads into the progress lambda and gating timestamp text
 * to 1-second intervals, parent screens and lists do NOT recompose during playback ticks.
 */
@Composable
fun RealtimeTrackProgressBar(
    playbackPositionFlow: StateFlow<Long>?,
    isActive: Boolean,
    durationMs: Long,
    modifier: Modifier = Modifier,
    height: Dp = 3.5.dp,
    color: Color = BentoPrimary,
    trackColor: Color = BentoBorder.copy(alpha = 0.30f),
    showTimestamps: Boolean = false,
    fontSize: TextUnit = 10.sp
) {
    if (durationMs <= 0L) return

    val posState = if (isActive && playbackPositionFlow != null) {
        playbackPositionFlow.collectAsStateWithLifecycle()
    } else null

    Column(modifier = modifier) {
        LinearProgressIndicator(
            progress = {
                val currentPos = posState?.value ?: 0L
                (currentPos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape),
            color = color,
            trackColor = trackColor
        )

        if (showTimestamps && isActive) {
            Spacer(modifier = Modifier.height(3.dp))
            val durSec = (durationMs / 1000L).coerceAtLeast(0L)
            val posSec by remember(posState, durSec) {
                derivedStateOf {
                    ((posState?.value ?: 0L) / 1000L).coerceIn(0L, durSec)
                }
            }
            val posStr = remember(posSec) { String.format(Locale.US, "%d:%02d", posSec / 60L, posSec % 60L) }
            val durStr = remember(durSec) { String.format(Locale.US, "%d:%02d", durSec / 60L, durSec % 60L) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(posStr, fontSize = fontSize, color = color)
                Text(durStr, fontSize = fontSize, color = BentoTextSecondary)
            }
        }
    }
}

