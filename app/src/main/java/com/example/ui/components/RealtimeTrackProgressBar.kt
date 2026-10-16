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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * By isolating collection of [playbackPositionFlow] within this leaf composable,
 * parent screens and lists do NOT recompose during playback, ensuring zero UI lag.
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

    val currentPos = if (isActive && playbackPositionFlow != null) {
        val pos by playbackPositionFlow.collectAsState()
        pos
    } else 0L

    Column(modifier = modifier) {
        LinearProgressIndicator(
            progress = {
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
            val durSec = (durationMs / 1000).coerceAtLeast(0)
            val posSec = (currentPos / 1000).coerceIn(0L, durSec)
            val posStr = String.format(Locale.US, "%d:%02d", posSec / 60, posSec % 60)
            val durStr = String.format(Locale.US, "%d:%02d", durSec / 60, durSec % 60)
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
