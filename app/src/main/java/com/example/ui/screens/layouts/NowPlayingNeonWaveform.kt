package com.example.ui.screens.layouts

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.components.AlbumArtView
import com.example.ui.theme.*
import com.example.util.AudioRouteInfo
import com.example.util.HapticUtils
import kotlin.math.sin

@Composable
fun NowPlayingNeonWaveform(
    playbackState: PlaybackState,
    positionMs: Long = playbackState.positionMs,
    currentLyrics: Lyrics?,
    audioRouteInfo: AudioRouteInfo,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onFavoriteToggle: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onOpenAudioOutputSwitcher: () -> Unit,
    onOpenQueueSheet: () -> Unit,
    onOpenLyricsSheet: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenOptionsMenu: () -> Unit = {},
    waveformStyle: String = "BARS",
    gesturePlaybackEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val context = LocalContext.current

    val duration = (if (playbackState.durationMs > 0L) playbackState.durationMs else song.durationMs).coerceAtLeast(1L)
    val safePos = positionMs.coerceIn(0L, duration)
    val progress = (safePos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return "%d:%02d".format(mins, secs)
    }

    val neonAccent = BentoPrimary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close", tint = BentoTextPrimary, modifier = Modifier.size(28.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = CircleShape, color = neonAccent, modifier = Modifier.size(6.dp)) {}
                    Text("NEON SOUNDWAVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = neonAccent, letterSpacing = 1.2.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onOpenOptionsMenu, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onOpenQueueSheet, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Neon Bordered Cover
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(contentAlignment = Alignment.Center) {
                    val coverSize = minOf(maxHeight * 0.95f, maxWidth * 0.75f, 230.dp)
                    com.example.ui.components.AlbumArtGestureBox(
                        enabled = gesturePlaybackEnabled,
                        isPlaying = playbackState.isPlaying,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = BentoCardSurface,
                            border = androidx.compose.foundation.BorderStroke(
                                2.dp,
                                Brush.linearGradient(listOf(neonAccent, BentoPrimary, BentoAccentPinkText))
                            ),
                            modifier = Modifier.size(coverSize)
                        ) {
                            AlbumArtView(
                                song = song,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(22.dp)),
                                cornerRadius = 22
                            )
                        }
                    }
                }
            }

            // Track Info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = song.displayTitle,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.displayArtist,
                    fontSize = 13.5.sp,
                    color = neonAccent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Interactive Neon Soundwave Seek Bar
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                com.example.ui.components.NowPlayingWaveformVisualizer(
                    trackId = song.id,
                    progressMs = safePos,
                    durationMs = duration,
                    isPlaying = playbackState.isPlaying,
                    style = waveformStyle,
                    accentColor = neonAccent,
                    onSeekTo = onSeekTo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BentoCardSurface.copy(alpha = 0.8f))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(safePos), fontSize = 11.sp, color = BentoTextSecondary)
                    Text(formatTime(duration), fontSize = 11.sp, color = BentoTextSecondary)
                }
            }

            // Transport Control
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = if (playbackState.isShuffleEnabled) neonAccent else BentoTextSecondary)
                }
                IconButton(onClick = onSkipPrevious, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = BentoTextPrimary, modifier = Modifier.size(30.dp))
                }
                Surface(
                    shape = CircleShape,
                    color = neonAccent,
                    modifier = Modifier
                        .size(62.dp)
                        .clickable {
                            HapticUtils.performClickHaptic(context)
                            onTogglePlayPause()
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = BentoOnPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                IconButton(onClick = onSkipNext, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = BentoTextPrimary, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onToggleRepeat, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (playbackState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) neonAccent else BentoTextSecondary
                    )
                }
            }

            // Bottom Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(36.dp)) {
                    Icon(if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favorite", tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenLyricsSheet, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = BentoTextSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenAudioOutputSwitcher, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.SpeakerGroup, contentDescription = "Output", tint = neonAccent, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenSleepTimer, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Bedtime, contentDescription = "Sleep", tint = BentoTextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
