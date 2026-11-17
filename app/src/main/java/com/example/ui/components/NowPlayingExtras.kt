package com.example.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.util.AudioTechnicalInfo
import kotlin.math.roundToInt

/**
 * Sleek hardware-synced media volume seekbar for all 4 Now Playing screens.
 * Uses local drag state and only invokes AudioManager.setStreamVolume when
 * the discrete integer volume step changes to keep 60/120fps drag silky smooth.
 */
@Composable
fun NowPlayingVolumeBar(
    inkColor: Color = Color.White,
    accentColor: Color = inkColor,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVolume = remember(audioManager) {
        (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
    }

    var isDragging by remember { mutableStateOf(false) }
    var volumeFraction by remember {
        val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        mutableFloatStateOf((current.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f))
    }

    DisposableEffect(context, audioManager, maxVolume) {
        if (audioManager == null) {
            onDispose {}
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (!isDragging && intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                        if (streamType == -1 || streamType == AudioManager.STREAM_MUSIC) {
                            val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            volumeFraction = (vol.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f)
                        }
                    }
                }
            }
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            try {
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (_: Throwable) {}
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 4.dp,
        animationSpec = tween(140),
        label = "volume_track_height"
    )

    fun applyFractionToStream(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        volumeFraction = clamped
        val targetVol = (clamped * maxVolume).roundToInt().coerceIn(0, maxVolume)
        val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: targetVol
        if (audioManager != null && targetVol != currentVol) {
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .testTag("now_playing_volume_seekbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (volumeFraction <= 0.01f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeDown,
            contentDescription = "Minimum volume",
            tint = inkColor.copy(alpha = 0.68f),
            modifier = Modifier.size(18.dp)
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .pointerInput(maxVolume) {
                    detectTapGestures { offset ->
                        if (size.width > 0) {
                            applyFractionToStream(offset.x / size.width.toFloat())
                        }
                    }
                }
                .pointerInput(maxVolume) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            if (size.width > 0) {
                                applyFractionToStream(offset.x / size.width.toFloat())
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (size.width > 0) {
                                applyFractionToStream(volumeFraction + dragAmount.x / size.width.toFloat())
                            }
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val fillFrac = volumeFraction.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(inkColor.copy(alpha = 0.22f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fillFrac)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.92f))
                )
            }

            if (isDragging) {
                val thumbOffset = (maxWidth - 10.dp) * fillFrac
                Box(
                    modifier = Modifier
                        .offset(x = thumbOffset)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(inkColor)
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Maximum volume",
            tint = inkColor.copy(alpha = 0.68f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Compact audio telemetry badge showing format, bit depth, sample rate (kHz), and bitrate (kbps).
 */
@Composable
fun AdvancedSongInfoRow(
    info: AudioTechnicalInfo?,
    inkColor: Color = Color.White,
    centered: Boolean = true,
    usePillSurface: Boolean = true,
    modifier: Modifier = Modifier
) {
    val summary = remember(info) { info?.formatSummary().orEmpty() }
    if (summary.isBlank()) return

    if (usePillSurface) {
        Surface(
            shape = RoundedCornerShape(50),
            color = inkColor.copy(alpha = 0.11f),
            border = BorderStroke(0.8.dp, inkColor.copy(alpha = 0.18f)),
            modifier = modifier.testTag("now_playing_advanced_song_info")
        ) {
            Text(
                text = summary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = inkColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    } else {
        Text(
            text = summary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = inkColor.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
            modifier = modifier.testTag("now_playing_advanced_song_info")
        )
    }
}
