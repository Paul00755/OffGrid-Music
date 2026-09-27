package com.example.ui.screens.layouts

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

@Composable
fun NowPlayingMinimalGlass(
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
    waveformStyle: String = "SNAKE",
    gesturePlaybackEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val context = LocalContext.current

    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val currentPos = if (isSeeking) seekPosition.toLong() else positionMs
    val duration = (if (playbackState.durationMs > 0L) playbackState.durationMs else song.durationMs).coerceAtLeast(1L)
    val sliderValue = (currentPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return "%d:%02d".format(mins, secs)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBackground)
            .background(
                Brush.verticalGradient(
                    listOf(
                        BentoBackground,
                        BentoCardSurface,
                        BentoBackground
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp),
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
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close", tint = BentoTextPrimary, modifier = Modifier.size(26.dp))
                }
                Text("MINIMAL GLASS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BentoPrimary, letterSpacing = 1.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onOpenOptionsMenu, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onOpenAudioOutputSwitcher, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.SpeakerGroup, contentDescription = "Audio Output", tint = BentoPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Glass Frosted Card Artwork
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(contentAlignment = Alignment.Center) {
                    val cardSize = minOf(maxHeight * 0.95f, maxWidth * 0.88f, 320.dp)
                    com.example.ui.components.AlbumArtGestureBox(
                        enabled = gesturePlaybackEnabled,
                        isPlaying = playbackState.isPlaying,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext
                    ) {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = BentoCardSurface.copy(alpha = 0.7f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .size(cardSize)
                                .padding(8.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                                AlbumArtView(
                                    song = song,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(20.dp)),
                                    cornerRadius = 20
                                )
                            }
                        }
                    }
                }
            }

            // Title & Artist
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = song.displayTitle,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.displayArtist,
                    fontSize = 14.sp,
                    color = BentoTextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Waveform & Progress Seeker
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.example.ui.components.NowPlayingWaveformVisualizer(
                    progressMs = currentPos,
                    durationMs = duration,
                    isPlaying = playbackState.isPlaying,
                    style = waveformStyle,
                    accentColor = BentoPrimary,
                    onSeekTo = onSeekTo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(currentPos), fontSize = 11.sp, color = BentoTextSecondary)
                    Text(formatTime(duration), fontSize = 11.sp, color = BentoTextSecondary)
                }
            }

            // Glass Pill Transport Control
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = BentoCardSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = if (playbackState.isShuffleEnabled) BentoPrimary else BentoTextSecondary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onSkipPrevious, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = BentoTextPrimary, modifier = Modifier.size(28.dp))
                    }
                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onTogglePlayPause()
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(BentoPrimary)
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = BentoOnPrimary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    IconButton(onClick = onSkipNext, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = BentoTextPrimary, modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = onToggleRepeat, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (playbackState.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (playbackState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) BentoPrimary else BentoTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Bottom Actions (Swap: Sleep Timer in pos 2, Up-Next Queue in pos 3, Lyrics in pos 4)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(36.dp)) {
                    Icon(if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favorite", tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenSleepTimer, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Bedtime, contentDescription = "Sleep Timer", tint = BentoTextSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenQueueSheet, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "Up-Next Queue", tint = BentoPrimary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenLyricsSheet, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = BentoTextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
