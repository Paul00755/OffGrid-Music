package com.example.ui.screens.layouts

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Lyrics
import com.example.service.PlaybackState
import com.example.ui.components.AlbumArtView
import com.example.ui.theme.*
import com.example.util.AudioRouteInfo
import com.example.util.HapticUtils
import kotlinx.coroutines.flow.StateFlow

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
    playbackPositionFlow: StateFlow<Long>? = null,
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val context = LocalContext.current

    val duration = (if (playbackState.durationMs > 0L) playbackState.durationMs else song.durationMs).coerceAtLeast(1L)

    fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return "%d:%02d".format(mins, secs)
    }

    val neonAccent = BentoPrimary
    val neonSecondary = BentoAccentPinkText

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBackground)
    ) {
        // Dual-tone Neon Studio ambient backdrop glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            neonAccent.copy(alpha = 0.16f),
                            neonSecondary.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Neon Studio Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, neonAccent.copy(alpha = 0.35f)),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Close",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Surface(
                    onClick = onOpenAudioOutputSwitcher,
                    shape = RoundedCornerShape(50),
                    color = BentoCardSurface,
                    border = BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(listOf(neonAccent.copy(alpha = 0.6f), neonSecondary.copy(alpha = 0.5f)))
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (playbackState.isPlaying) neonAccent else BentoTextSecondary,
                            modifier = Modifier.size(7.dp)
                        ) {}
                        Text(
                            text = "NEON STUDIO • ${audioRouteInfo.name.uppercase()}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = neonAccent,
                            letterSpacing = 1.sp,
                            maxLines = 1
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        onClick = onOpenQueueSheet,
                        shape = RoundedCornerShape(14.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Surface(
                        onClick = onOpenOptionsMenu,
                        shape = RoundedCornerShape(14.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // 2. Neon Studio Framed Cover Showcase + Track Monitor
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(contentAlignment = Alignment.Center) {
                    val coverSize = minOf(maxHeight * 0.94f, maxWidth * 0.80f, 250.dp)
                    com.example.ui.components.AlbumArtGestureBox(
                        enabled = gesturePlaybackEnabled,
                        isPlaying = playbackState.isPlaying,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext
                    ) {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = BentoCardSurface,
                            border = BorderStroke(
                                2.dp,
                                Brush.linearGradient(listOf(neonAccent, BentoPrimary, neonSecondary))
                            ),
                            shadowElevation = 12.dp,
                            modifier = Modifier.size(coverSize)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AlbumArtView(
                                    song = song,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(24.dp)),
                                    cornerRadius = 24
                                )

                                // Floating Studio Live Badge
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = BentoBackground.copy(alpha = 0.80f),
                                    border = BorderStroke(1.dp, neonAccent.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = if (playbackState.isPlaying) "STUDIO LIVE" else "MONITOR",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = neonAccent,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                // Floating Favorite Action on Cover
                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onFavoriteToggle()
                                    },
                                    shape = CircleShape,
                                    color = BentoBackground.copy(alpha = 0.82f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (song.isFavorite) neonSecondary else BentoBorder.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(12.dp)
                                        .size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = "Favorite",
                                            tint = if (song.isFavorite) neonSecondary else BentoTextPrimary,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Neon Soundwave & Track Telemetry Console Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = BentoCardSurface,
                border = BorderStroke(
                    1.2.dp,
                    Brush.horizontalGradient(
                        listOf(
                            neonAccent.copy(alpha = 0.45f),
                            BentoBorder.copy(alpha = 0.4f),
                            neonSecondary.copy(alpha = 0.45f)
                        )
                    )
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = song.displayTitle,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${song.displayArtist} • ${song.displayAlbum}",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = neonAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            onClick = onOpenLyricsSheet,
                            shape = RoundedCornerShape(12.dp),
                            color = BentoCardDarkPurple,
                            border = BorderStroke(1.dp, neonAccent.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = neonAccent, modifier = Modifier.size(15.dp))
                                Text("LYRICS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary, letterSpacing = 0.6.sp)
                            }
                        }
                    }

                    // Interactive Neon Soundwave Visualizer
                    com.example.ui.components.NowPlayingWaveformVisualizer(
                        trackId = song.id,
                        progressMs = positionMs,
                        durationMs = duration,
                        isPlaying = playbackState.isPlaying,
                        style = waveformStyle,
                        accentColor = neonAccent,
                        onSeekTo = onSeekTo,
                        playbackPositionFlow = playbackPositionFlow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(BentoBackground.copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.example.ui.components.PlaybackPositionText(
                            playbackPositionFlow,
                            positionMs,
                            duration,
                            fontSize = 11.5.sp,
                            color = neonAccent
                        )
                        Text(
                            text = "WAVEFORM • ${waveformStyle.uppercase()}",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            letterSpacing = 0.8.sp
                        )
                        Text(formatTime(duration), fontSize = 11.5.sp, color = BentoTextSecondary)
                    }
                }
            }

            // 4. Sculpted Neon Studio Transport & Action Deck
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (playbackState.isShuffleEnabled) neonAccent else BentoTextSecondary
                        )
                    }
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onSkipPrevious()
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, neonAccent.copy(alpha = 0.35f)),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = BentoTextPrimary, modifier = Modifier.size(28.dp))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = neonAccent,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .width(82.dp)
                            .height(58.dp)
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
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onSkipNext()
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, neonAccent.copy(alpha = 0.35f)),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = BentoTextPrimary, modifier = Modifier.size(28.dp))
                        }
                    }
                    IconButton(onClick = onToggleRepeat, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = if (playbackState.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (playbackState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) neonAccent else BentoTextSecondary
                        )
                    }
                }

                // Bottom Studio Action Pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onOpenAudioOutputSwitcher,
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.SpeakerGroup, contentDescription = "Output", tint = neonAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Output", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                        }
                    }
                    Surface(
                        onClick = onOpenSleepTimer,
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bedtime, contentDescription = "Sleep", tint = neonAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Sleep Timer", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                        }
                    }
                    Surface(
                        onClick = onOpenQueueSheet,
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = neonAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Up Next", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                        }
                    }
                }
            }
        }
    }
}
