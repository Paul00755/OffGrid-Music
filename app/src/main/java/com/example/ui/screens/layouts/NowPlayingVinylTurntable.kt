package com.example.ui.screens.layouts

import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val context = LocalContext.current

    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (playbackState.isPlaying) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 4000,
                easing = LinearEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "vinyl_angle"
    )

    var isSeeking by remember(song.id) { mutableStateOf(false) }
    var seekPosition by remember(song.id) { mutableFloatStateOf(0f) }

    val duration = (if (playbackState.durationMs > 0L) playbackState.durationMs else song.durationMs).coerceAtLeast(1L)
    val safePos = positionMs.coerceIn(0L, duration)
    val currentPos = if (isSeeking) seekPosition.toLong() else safePos
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
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse", tint = BentoTextPrimary, modifier = Modifier.size(28.dp))
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "VINYL TURNTABLE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoPrimary,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = audioRouteInfo.name,
                        fontSize = 11.sp,
                        color = BentoTextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onOpenOptionsMenu, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextPrimary, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = onOpenQueueSheet, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = BentoTextPrimary, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // Realistic Spinning Vinyl Turntable Platter
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(contentAlignment = Alignment.Center) {
                    val platterSize = minOf(maxHeight * 0.95f, maxWidth * 0.9f, 290.dp)
                    val labelSize = platterSize * (115f / 290f)

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
                                .background(Color(0xFF14121A)),
                            contentAlignment = Alignment.Center
                        ) {
                            // Vinyl Grooves Canvas
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        rotationZ = if (playbackState.isPlaying) rotationAngle else 0f
                                    }
                            ) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val maxRadius = size.width / 2f

                                // Outer Vinyl Ring
                                drawCircle(color = Color(0xFF1F1D27), radius = maxRadius)

                                // Concentric Grooves
                                for (r in 40..maxRadius.toInt() step 7) {
                                    drawCircle(
                                        color = Color(0xFF110E18),
                                        radius = r.toFloat(),
                                        center = center,
                                        style = Stroke(width = 1.2f)
                                    )
                                }

                                // Shimmer highlights
                                drawCircle(
                                    brush = Brush.sweepGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.08f),
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.08f),
                                            Color.Transparent
                                        ),
                                        center = center
                                    ),
                                    radius = maxRadius,
                                    center = center
                                )
                            }

                            // Centered Album Artwork Label
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
                                // Center Spindle Hole
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF0C0911),
                                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF33313B)),
                                    modifier = Modifier.size(18.dp)
                                ) {}
                            }
                        }
                    }
                }
            }

            // Song Info & Favorite
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
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
                    text = "${song.displayArtist} • ${song.displayAlbum}",
                    fontSize = 13.5.sp,
                    color = BentoTextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Seek Bar & Waveform Visualization
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                com.example.ui.components.NowPlayingWaveformVisualizer(
                    trackId = song.id,
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
                    Text(formatTime(currentPos), fontSize = 11.5.sp, color = BentoTextSecondary)
                    Text(formatTime(duration), fontSize = 11.5.sp, color = BentoTextSecondary)
                }
            }

            // Transport Control Deck
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playbackState.isShuffleEnabled) BentoPrimary else BentoTextSecondary
                    )
                }

                IconButton(onClick = onSkipPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = BentoTextPrimary, modifier = Modifier.size(32.dp))
                }

                Surface(
                    shape = CircleShape,
                    color = BentoPrimary,
                    modifier = Modifier
                        .size(68.dp)
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
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                IconButton(onClick = onSkipNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = BentoTextPrimary, modifier = Modifier.size(32.dp))
                }

                IconButton(onClick = onToggleRepeat, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (playbackState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) BentoPrimary else BentoTextSecondary
                    )
                }
            }

            // Bottom Auxiliary Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onOpenLyricsSheet, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = BentoTextSecondary, modifier = Modifier.size(20.dp))
                }

                IconButton(onClick = onOpenAudioOutputSwitcher, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.SpeakerGroup, contentDescription = "Audio Output", tint = BentoPrimary, modifier = Modifier.size(20.dp))
                }

                IconButton(onClick = onOpenSleepTimer, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Bedtime, contentDescription = "Sleep Timer", tint = BentoTextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
