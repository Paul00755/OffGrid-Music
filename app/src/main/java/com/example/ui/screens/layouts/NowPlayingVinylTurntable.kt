package com.example.ui.screens.layouts

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingVinylTurntable(
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
    playbackPositionFlow: StateFlow<Long>? = null,
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val context = LocalContext.current

    val rotationAngle = if (playbackState.isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin")
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 4200,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "vinyl_angle"
        )
        angle
    } else {
        0f
    }

    // Tonearm pivots smoothly onto the vinyl platter when playing
    val tonearmAngle by animateFloatAsState(
        targetValue = if (playbackState.isPlaying) 24f else 2f,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "tonearm_angle"
    )

    val duration = (if (playbackState.durationMs > 0L) playbackState.durationMs else song.durationMs).coerceAtLeast(1L)

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
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Analog Deck Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Collapse",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Surface(
                    onClick = onOpenAudioOutputSwitcher,
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (playbackState.isPlaying) BentoPrimary else BentoTextSecondary)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "VINYL TURNTABLE • 33⅓ RPM",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoPrimary,
                                letterSpacing = 1.1.sp
                            )
                            Text(
                                text = audioRouteInfo.name,
                                fontSize = 10.5.sp,
                                color = BentoTextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        onClick = onOpenQueueSheet,
                        shape = CircleShape,
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
                        shape = CircleShape,
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

            // 2. Turntable Plinth Enclosure & Spinning Vinyl Platter + Tonearm
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = BentoCardSurface,
                border = BorderStroke(1.5.dp, BentoBorder.copy(alpha = 0.65f)),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 6.dp)
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    BentoCardDarkPurple.copy(alpha = 0.85f),
                                    BentoCardSurface
                                )
                            )
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val platterSize = minOf(maxHeight * 0.92f, maxWidth * 0.82f, 268.dp)
                    val labelSize = platterSize * 0.40f

                    // Corner Brushed Metal Deck Screws / Accents
                    val screwColor = BentoBorder.copy(alpha = 0.7f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(screwColor)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(screwColor)
                    )
                    // Bottom-Start Strobe Power Indicator
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (playbackState.isPlaying) Color(0xFFFF5252) else BentoTextSecondary.copy(alpha = 0.4f))
                        )
                        Text(
                            text = if (playbackState.isPlaying) "QUARTZ LOCK" else "STANDBY",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            letterSpacing = 0.8.sp
                        )
                    }

                    // Spinning Platter
                    com.example.ui.components.AlbumArtGestureBox(
                        enabled = gesturePlaybackEnabled,
                        isPlaying = playbackState.isPlaying,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext
                    ) {
                        Box(
                            modifier = Modifier
                                .size(platterSize)
                                .clip(CircleShape)
                                .background(Color(0xFF100E16)),
                            contentAlignment = Alignment.Center
                        ) {
                            // Vinyl Grooves & Strobe Rim Canvas
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        rotationZ = if (playbackState.isPlaying) rotationAngle else 0f
                                    }
                            ) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val maxRadius = size.width / 2f

                                // Metallic Strobe Rim
                                drawCircle(color = Color(0xFF282534), radius = maxRadius)
                                drawCircle(color = Color(0xFF121018), radius = maxRadius * 0.95f)

                                // Strobe dots around outer rim
                                val dotCount = 36
                                for (i in 0 until dotCount) {
                                    val rad = Math.toRadians((i * (360.0 / dotCount)))
                                    val dotRadius = maxRadius * 0.972f
                                    val dx = center.x + (dotRadius * cos(rad)).toFloat()
                                    val dy = center.y + (dotRadius * sin(rad)).toFloat()
                                    drawCircle(
                                        color = Color.White.copy(alpha = 0.42f),
                                        radius = 2.2f,
                                        center = Offset(dx, dy)
                                    )
                                }

                                // Concentric Vinyl Micro-Grooves
                                val grooveStart = (maxRadius * 0.43f).toInt()
                                val grooveEnd = (maxRadius * 0.92f).toInt()
                                for (r in grooveStart..grooveEnd step 6) {
                                    drawCircle(
                                        color = Color(0xFF1B1824),
                                        radius = r.toFloat(),
                                        center = center,
                                        style = Stroke(width = 1.15f)
                                    )
                                }

                                // Anisotropic Vinyl Sheen Highlights
                                drawCircle(
                                    brush = Brush.sweepGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.09f),
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.09f),
                                            Color.Transparent
                                        ),
                                        center = center
                                    ),
                                    radius = maxRadius * 0.94f,
                                    center = center
                                )
                            }

                            // Centered Album Artwork Record Label
                            Box(
                                modifier = Modifier
                                    .size(labelSize)
                                    .graphicsLayer {
                                        rotationZ = if (playbackState.isPlaying) rotationAngle else 0f
                                    }
                                    .clip(CircleShape)
                                    .background(BentoPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                AlbumArtView(
                                    song = song,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    cornerRadius = 100
                                )
                                // Center Spindle Adapter Ring & Hole
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF0C0911),
                                    border = BorderStroke(2.5.dp, Color(0xFF423E4F)),
                                    modifier = Modifier.size(18.dp)
                                ) {}
                            }
                        }
                    }

                    // Pivoting Tonearm Assembly on Top-Right
                    val accentColor = BentoPrimary
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { clip = false }
                    ) {
                        val pivotX = size.width * 0.87f
                        val pivotY = size.height * 0.16f
                        val pivot = Offset(pivotX, pivotY)

                        // Tonearm gimbal base
                        drawCircle(color = Color(0xFF2B2838), radius = 24f, center = pivot)
                        drawCircle(color = accentColor.copy(alpha = 0.55f), radius = 15f, center = pivot)
                        drawCircle(color = Color(0xFF14121C), radius = 8f, center = pivot)

                        // Calculate tonearm shaft end based on animated tonearmAngle
                        val armLength = size.height * 0.56f
                        val angleRad = Math.toRadians((95.0 + tonearmAngle))
                        val endX = pivotX + (armLength * cos(angleRad)).toFloat()
                        val endY = pivotY + (armLength * sin(angleRad)).toFloat()

                        // Tonearm tube
                        drawLine(
                            color = Color(0xFFB5B2C2),
                            start = pivot,
                            end = Offset(endX, endY),
                            strokeWidth = 6f,
                            cap = StrokeCap.Round
                        )

                        // Cartridge headshell at the tip
                        drawRoundRect(
                            color = accentColor,
                            topLeft = Offset(endX - 9f, endY - 5f),
                            size = Size(18f, 12f),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                    }
                }
            }

            // 3. Analog Track Console & Waveform Seek Module
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${song.displayArtist} • ${song.displayAlbum}",
                                fontSize = 12.5.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onFavoriteToggle()
                            },
                            shape = CircleShape,
                            color = if (song.isFavorite) BentoAccentPinkText.copy(alpha = 0.16f) else BentoCardDarkPurple,
                            border = BorderStroke(
                                1.dp,
                                if (song.isFavorite) BentoAccentPinkText.copy(alpha = 0.45f) else BentoBorder.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }

                    // Waveform & Timestamps
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        com.example.ui.components.NowPlayingWaveformVisualizer(
                            trackId = song.id,
                            progressMs = positionMs,
                            durationMs = duration,
                            isPlaying = playbackState.isPlaying,
                            style = waveformStyle,
                            accentColor = BentoPrimary,
                            onSeekTo = onSeekTo,
                            playbackPositionFlow = playbackPositionFlow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            com.example.ui.components.PlaybackPositionText(
                                playbackPositionFlow,
                                positionMs,
                                duration,
                                fontSize = 11.5.sp,
                                color = BentoPrimary
                            )
                            Text(formatTime(duration), fontSize = 11.5.sp, color = BentoTextSecondary)
                        }
                    }
                }
            }

            // 4. Tactile Hardware Transport Deck
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle, modifier = Modifier.size(42.dp)) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (playbackState.isShuffleEnabled) BentoPrimary else BentoTextSecondary
                        )
                    }

                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onSkipPrevious()
                        },
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = BentoTextPrimary, modifier = Modifier.size(28.dp))
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = BentoPrimary,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .size(66.dp)
                            .clickable {
                                HapticUtils.performClickHaptic(context)
                                onTogglePlayPause()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
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
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = BentoTextPrimary, modifier = Modifier.size(28.dp))
                        }
                    }

                    IconButton(onClick = onToggleRepeat, modifier = Modifier.size(42.dp)) {
                        Icon(
                            imageVector = if (playbackState.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (playbackState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) BentoPrimary else BentoTextSecondary
                        )
                    }
                }

                // Bottom Studio Utility Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onOpenLyricsSheet,
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = BentoPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Lyrics", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                        }
                    }

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
                            Icon(Icons.Default.SpeakerGroup, contentDescription = "Audio Output", tint = BentoPrimary, modifier = Modifier.size(16.dp))
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
                            Icon(Icons.Default.Bedtime, contentDescription = "Sleep Timer", tint = BentoPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Sleep", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                        }
                    }
                }
            }
        }
    }
}
