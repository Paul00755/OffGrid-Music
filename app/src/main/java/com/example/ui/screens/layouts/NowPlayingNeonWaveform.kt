package com.example.ui.screens.layouts

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    val neonCyan = BentoPrimary
    val neonMagenta = BentoAccentPinkText

    val pulseScale = if (playbackState.isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "neon_aura")
        val anim by infiniteTransition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "aura_pulse"
        )
        anim
    } else {
        1f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBackground)
    ) {
        // Holographic Neon Laser & Concentric Aura Stage Canvas (Frameless, Non-Boxy)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerTop = Offset(size.width * 0.5f, size.height * 0.31f)
            val maxRadius = size.minDimension * 0.46f * pulseScale

            // Radial dual-tone neon aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        neonCyan.copy(alpha = 0.24f),
                        neonMagenta.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = centerTop,
                    radius = maxRadius * 1.35f
                ),
                radius = maxRadius * 1.35f,
                center = centerTop
            )

            // Outer holographic ring
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        neonCyan.copy(alpha = 0.55f),
                        neonMagenta.copy(alpha = 0.45f),
                        neonCyan.copy(alpha = 0.15f),
                        neonMagenta.copy(alpha = 0.55f),
                        neonCyan.copy(alpha = 0.55f)
                    ),
                    center = centerTop
                ),
                radius = maxRadius * 0.88f,
                center = centerTop,
                style = Stroke(width = 2.dp.toPx())
            )

            // Secondary outer pulse ring
            drawCircle(
                color = neonCyan.copy(alpha = 0.18f),
                radius = maxRadius * 1.02f,
                center = centerTop,
                style = Stroke(width = 1.dp.toPx())
            )

            // Bottom neon horizon glow behind transport
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        neonMagenta.copy(alpha = 0.16f),
                        neonCyan.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.82f),
                    radius = size.width * 0.65f
                ),
                radius = size.width * 0.65f,
                center = Offset(size.width * 0.5f, size.height * 0.82f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Floating Glassmorphic Neon Header Pill Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = BentoCardSurface.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.45f)),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Collapse",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Floating Neon Studio Output Pill
                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onOpenAudioOutputSwitcher()
                    },
                    shape = RoundedCornerShape(50),
                    color = BentoCardSurface.copy(alpha = 0.72f),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.horizontalGradient(listOf(neonCyan, neonMagenta))
                    ),
                    modifier = Modifier.testTag("audio_output_route_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (playbackState.isPlaying) neonCyan else BentoTextSecondary)
                        )
                        Icon(
                            imageVector = audioRouteInfo.icon,
                            contentDescription = "Audio Output Device",
                            tint = neonCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = audioRouteInfo.name.uppercase(),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = BentoTextPrimary,
                            letterSpacing = 1.sp,
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onOpenOptionsMenu()
                    },
                    shape = CircleShape,
                    color = BentoCardSurface.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, neonMagenta.copy(alpha = 0.45f)),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 2. Circular Holographic Portal Centerpiece (Non-Boxy!)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(contentAlignment = Alignment.Center) {
                    val portalDiameter = minOf(maxHeight * 0.92f, maxWidth * 0.74f, 248.dp)
                    Box(
                        modifier = Modifier.size(portalDiameter + 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Circular Neon Rim + Album Art Portal
                        com.example.ui.components.AlbumArtGestureBox(
                            enabled = gesturePlaybackEnabled,
                            isPlaying = playbackState.isPlaying,
                            onTogglePlayPause = onTogglePlayPause,
                            onSkipPrevious = onSkipPrevious,
                            onSkipNext = onSkipNext
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(portalDiameter)
                                    .clip(CircleShape)
                                    .border(
                                        width = 3.dp,
                                        brush = Brush.sweepGradient(
                                            listOf(neonCyan, neonMagenta, neonCyan, neonMagenta, neonCyan)
                                        ),
                                        shape = CircleShape
                                    )
                                    .padding(6.dp)
                                    .clip(CircleShape)
                            ) {
                                AlbumArtView(
                                    song = song,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    cornerRadius = 200
                                )
                            }
                        }

                        // Floating Neon Status Pill at Top of Portal
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = BentoBackground.copy(alpha = 0.88f),
                            border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.7f)),
                            modifier = Modifier.align(Alignment.TopCenter)
                        ) {
                            Text(
                                text = if (playbackState.isPlaying) "⚡ NEON SYNTH STAGE" else "STANDBY STAGE",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = neonCyan,
                                letterSpacing = 1.2.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        // Floating Circular Neon Favorite Orb on Bottom-End of Portal
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onFavoriteToggle()
                            },
                            shape = CircleShape,
                            color = BentoBackground.copy(alpha = 0.90f),
                            border = BorderStroke(
                                1.5.dp,
                                if (song.isFavorite) neonMagenta else neonCyan.copy(alpha = 0.6f)
                            ),
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 8.dp, bottom = 8.dp)
                                .size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (song.isFavorite) neonMagenta else BentoTextPrimary,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Frameless Centered Neon Track Typography & Live Spectrum Stage
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = song.displayTitle,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = BentoTextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "${song.displayArtist}  •  ${song.displayAlbum}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = neonCyan,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Frameless Full-Width Glowing Neon Spectrum Visualizer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp)
                ) {
                    // Subtle neon laser line behind the waveform center
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .align(Alignment.Center)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        neonCyan.copy(alpha = 0.45f),
                                        neonMagenta.copy(alpha = 0.45f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    com.example.ui.components.NowPlayingWaveformVisualizer(
                        trackId = song.id,
                        progressMs = positionMs,
                        durationMs = duration,
                        isPlaying = playbackState.isPlaying,
                        style = waveformStyle,
                        accentColor = neonCyan,
                        onSeekTo = onSeekTo,
                        playbackPositionFlow = playbackPositionFlow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    )
                }

                // Glowing Monospaced Timestamps & Mode Pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.example.ui.components.PlaybackPositionText(
                        playbackPositionFlow,
                        positionMs,
                        duration,
                        fontSize = 12.sp,
                        color = neonCyan
                    )

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = neonMagenta.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, neonMagenta.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "SPECTRUM • ${waveformStyle.uppercase()}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = neonMagenta,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }

                    Text(
                        text = formatTime(duration),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextSecondary
                    )
                }
            }

            // 4. Circular Neon Halo Transport Orbs + Floating Bottom Studio Dock Pill
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle Toggle
                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onToggleShuffle()
                        },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (playbackState.isShuffleEnabled) neonCyan else BentoTextSecondary,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // Circular Neon Rim Previous Button
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onSkipPrevious()
                        },
                        shape = CircleShape,
                        color = BentoCardSurface.copy(alpha = 0.55f),
                        border = BorderStroke(1.5.dp, neonCyan.copy(alpha = 0.55f)),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Center Glowing Dual-Tone Neon Play/Pause Orb
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(neonCyan, neonMagenta)
                                )
                            )
                            .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                            .clickable {
                                HapticUtils.performClickHaptic(context)
                                onTogglePlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = BentoOnPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Circular Neon Rim Next Button
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onSkipNext()
                        },
                        shape = CircleShape,
                        color = BentoCardSurface.copy(alpha = 0.55f),
                        border = BorderStroke(1.5.dp, neonMagenta.copy(alpha = 0.55f)),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Repeat Toggle
                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onToggleRepeat()
                        },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = if (playbackState.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (playbackState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) neonMagenta else BentoTextSecondary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }

                // Floating Glassmorphic Neon Capsule Dock (Lyrics, Output, Sleep Timer, Queue)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = BentoCardSurface.copy(alpha = 0.72f),
                    border = BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(
                                neonCyan.copy(alpha = 0.5f),
                                neonMagenta.copy(alpha = 0.5f)
                            )
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onOpenLyricsSheet()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = neonCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Lyrics", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(BentoBorder.copy(alpha = 0.5f))
                        )

                        TextButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onOpenAudioOutputSwitcher()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.SpeakerGroup, contentDescription = "Output", tint = neonMagenta, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Output", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(BentoBorder.copy(alpha = 0.5f))
                        )

                        TextButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onOpenSleepTimer()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Bedtime, contentDescription = "Timer", tint = neonCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Timer", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(BentoBorder.copy(alpha = 0.5f))
                        )

                        TextButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onOpenQueueSheet()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = neonMagenta, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Queue", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                        }
                    }
                }
            }
        }
    }
}
