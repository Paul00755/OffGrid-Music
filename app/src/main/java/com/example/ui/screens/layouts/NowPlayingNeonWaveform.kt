package com.example.ui.screens.layouts

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Lyrics
import com.example.service.PlaybackState
import com.example.ui.components.AdvancedSongInfoRow
import com.example.ui.components.AlbumArtView
import com.example.ui.components.NowPlayingVolumeBar
import com.example.ui.theme.*
import com.example.util.ArtworkColorExtractor
import com.example.util.AudioRouteInfo
import com.example.util.AudioTechnicalInfo
import com.example.util.HapticUtils
import kotlinx.coroutines.flow.StateFlow

/**
 * Redesigned Neon Studio layout inspired by the clean Apple-style full-art dual-tone gradient stage:
 * - Full-screen smooth vertical gradient extracted from the song's artwork colors
 * - Large centered rounded-square album cover without cluttered borders
 * - Sleek thin seekbar directly underneath with elapsed & negative remaining timestamps
 * - Centered bold track title & artist (+ optional advanced audio specs)
 * - Clean borderless 5-icon transport bar: Shuffle, Rewind, Play/Pause, Fast-Forward, Favorite
 * - Optional sleek volume seek bar
 * - Minimal bottom utility strip: More (•••), compact Lyrics/Queue/Timer/Repeat shortcuts, Audio Output
 */
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
    waveformStyle: String = "SLEEK_LINE",
    gesturePlaybackEnabled: Boolean = true,
    playbackPositionFlow: StateFlow<Long>? = null,
    showVolumeBar: Boolean = false,
    showAdvancedSongInfo: Boolean = false,
    audioTechnicalInfo: AudioTechnicalInfo? = null,
    showRemainingTime: Boolean = false,
    onToggleShowRemainingTime: () -> Unit = {},
    showSeekButtons: Boolean = false,
    backwardSeconds: Int = 10,
    forwardSeconds: Int = 10,
    onSeekBackward: () -> Unit = {},
    onSeekForward: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val context = LocalContext.current

    val duration = (if (playbackState.durationMs > 0L) playbackState.durationMs else song.durationMs).coerceAtLeast(1L)
    val livePosState = playbackPositionFlow?.collectAsStateWithLifecycle(initialValue = positionMs)
    val livePositionMs = (livePosState?.value ?: positionMs).coerceIn(0L, duration)

    val glassConfig = com.example.ui.theme.LocalGlassThemeConfig.current
    val baseBg = BentoBackground
    val primaryThemeColor = BentoPrimary
    val isDarkTheme = baseBg.luminance() < 0.5f
    val useRichDark = glassConfig.useDarkColors || glassConfig.isCustomDarkAccent

    val fallbackTop = primaryThemeColor.toAdaptiveThemeAccent(
        useDarkColors = useRichDark,
        preserveExactDark = glassConfig.isCustomDarkAccent
    )
    val fallbackBottom = glassConfig.secondaryTintColor.toAdaptiveCompanionHue(
        useDarkColors = useRichDark,
        preserveExactDark = glassConfig.isCustomDarkAccent
    )

    val initialCachedPair = remember(song.id, song.artworkUri, glassConfig.isDynamicArtworkEnabled) {
        if (glassConfig.isDynamicArtworkEnabled) {
            ArtworkColorExtractor.getCachedDualGradientForSongSync(context, song)
        } else {
            null
        }
    }
    var extractedPair by remember(song.id, song.artworkUri, glassConfig.isDynamicArtworkEnabled) {
        mutableStateOf(initialCachedPair)
    }
    LaunchedEffect(song.id, song.artworkUri, glassConfig.isDynamicArtworkEnabled) {
        if (glassConfig.isDynamicArtworkEnabled) {
            val extracted = ArtworkColorExtractor.extractDualGradientColorsForSong(context, song)
            if (extracted != null) {
                extractedPair = extracted
            }
        } else {
            extractedPair = null
        }
    }

    val activePair = if (glassConfig.isDynamicArtworkEnabled && extractedPair != null) {
        extractedPair!!
    } else {
        fallbackTop to fallbackBottom
    }

    val targetTopBg = remember(activePair.first, useRichDark) {
        if (useRichDark) activePair.first.ensureNeonDarkStage() else activePair.first.ensureNeonPastelStage()
    }
    val targetBottomBg = remember(activePair.second, useRichDark) {
        if (useRichDark) activePair.second.ensureNeonDarkStage() else activePair.second.ensureNeonPastelStage()
    }
    val targetMidBg = remember(targetTopBg, targetBottomBg) {
        androidx.compose.ui.graphics.lerp(targetTopBg, targetBottomBg, 0.48f)
    }

    val topBgColor by animateColorAsState(
        targetValue = targetTopBg,
        animationSpec = tween(460),
        label = "neon_top_gradient"
    )
    val midBgColor by animateColorAsState(
        targetValue = targetMidBg,
        animationSpec = tween(460),
        label = "neon_mid_gradient"
    )
    val bottomBgColor by animateColorAsState(
        targetValue = targetBottomBg,
        animationSpec = tween(460),
        label = "neon_bottom_gradient"
    )

    // Always render Neon Studio controls, waveforms, icons, and typography in crisp white
    val primaryInk = Color.White
    val secondaryInk = Color.White.copy(alpha = 0.78f)
    val topBarInk = Color.White
    val harmonizedTopAccent = Color.White

    var isDraggingSeek by remember(song.id) { mutableStateOf(false) }
    var dragFraction by remember(song.id) { mutableFloatStateOf(0f) }

    val activeFraction = if (isDraggingSeek) {
        dragFraction
    } else {
        (livePositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
    val effectiveSeekMs = if (isDraggingSeek) (dragFraction * duration).toLong() else livePositionMs
    val remainingMs = (duration - effectiveSeekMs).coerceAtLeast(0L)

    fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(totalSecs / 60L, totalSecs % 60L)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to topBgColor,
                    0.50f to midBgColor,
                    1.0f to bottomBgColor
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        val isDenseNeon = showSeekButtons && showVolumeBar
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = if (isDenseNeon) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isDenseNeon) 4.dp else 8.dp)
        ) {
            // Subtle Top Handle & Collapse Affordance
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse Now Playing",
                        tint = topBarInk.copy(alpha = 0.85f),
                        modifier = Modifier.size(26.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(topBarInk.copy(alpha = 0.35f))
                )
            }

            // Large Centered Rounded-Square Album Art (scales smoothly to preserve space for seek & volume controls)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = if (isDenseNeon) 2.dp else 4.dp),
                contentAlignment = Alignment.Center
            ) {
                val maxArtSize = minOf(maxWidth * 0.94f, maxHeight * 0.94f, 340.dp).coerceAtLeast(120.dp)
                com.example.ui.components.AlbumArtGestureBox(
                        enabled = gesturePlaybackEnabled,
                        isPlaying = playbackState.isPlaying,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext
                    ) {
                        Box(
                            modifier = Modifier
                                .size(maxArtSize)
                                .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp), clip = false)
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            AlbumArtView(
                                song = song,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 16,
                                forceHighResOnline = true
                            )
                        }
                    }
            }

            // Seek / Waveform Visualizer & Elapsed / Remaining Timestamps directly below Album Art
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                com.example.ui.components.NowPlayingWaveformVisualizer(
                    trackId = song.id,
                    progressMs = livePositionMs,
                    durationMs = duration,
                    isPlaying = playbackState.isPlaying,
                    style = waveformStyle,
                    accentColor = harmonizedTopAccent,
                    onSeekTo = onSeekTo,
                    playbackPositionFlow = playbackPositionFlow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .testTag("neon_studio_waveform_visualizer")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.example.ui.components.PlaybackPositionText(
                        playbackPositionFlow = playbackPositionFlow,
                        positionMs = livePositionMs,
                        durationMs = duration,
                        fontSize = 12.sp,
                        color = secondaryInk
                    )
                    com.example.ui.components.PlaybackDurationToggleText(
                        playbackPositionFlow = playbackPositionFlow,
                        positionMs = livePositionMs,
                        durationMs = duration,
                        showRemainingTime = showRemainingTime,
                        onToggleShowRemainingTime = onToggleShowRemainingTime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = secondaryInk
                    )
                }
            }

            // Centered Track Title & Artist (+ Optional Advanced Audio Specs)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = song.displayTitle,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryInk,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = song.displayArtist,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = secondaryInk,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showAdvancedSongInfo && audioTechnicalInfo != null) {
                    AdvancedSongInfoRow(
                        info = audioTechnicalInfo,
                        inkColor = secondaryInk,
                        centered = true,
                        usePillSurface = false
                    )
                }
            }

            // Borderless Spacious 5-Icon Transport Row (Shuffle | FastRewind | Play/Pause | FastForward | Favorite)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isShuffleActive = playbackState.isShuffleEnabled
                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onToggleShuffle()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isShuffleActive) Icons.Default.Shuffle else Icons.Outlined.Shuffle,
                        contentDescription = if (isShuffleActive) "Shuffle on" else "Shuffle off",
                        tint = if (isShuffleActive) harmonizedTopAccent else primaryInk.copy(alpha = 0.48f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onSkipPrevious()
                    },
                    modifier = Modifier.size(54.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "Previous",
                        tint = primaryInk,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onTogglePlayPause()
                    },
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = primaryInk,
                        modifier = Modifier.size(52.dp)
                    )
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onSkipNext()
                    },
                    modifier = Modifier.size(54.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Next",
                        tint = primaryInk,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onFavoriteToggle()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    com.example.ui.components.AnimatedFavoriteIcon(
                        isFavorite = song.isFavorite,
                        activeTint = Color.White,
                        inactiveTint = Color.White.copy(alpha = 0.90f),
                        size = 23.dp,
                        contentDescription = "Favorite",
                        animationKey = song.id
                    )
                }
            }

            if (showSeekButtons) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.example.ui.components.SeekIntervalButton(
                        seconds = backwardSeconds,
                        isForward = false,
                        onClick = onSeekBackward,
                        tint = primaryInk,
                        iconSize = 27.dp,
                        touchSize = 48.dp
                    )
                    com.example.ui.components.SeekIntervalButton(
                        seconds = forwardSeconds,
                        isForward = true,
                        onClick = onSeekForward,
                        tint = primaryInk,
                        iconSize = 27.dp,
                        touchSize = 48.dp
                    )
                }
            }

            // Optional Sleek Volume Seekbar
            if (showVolumeBar) {
                NowPlayingVolumeBar(
                    inkColor = primaryInk,
                    accentColor = harmonizedTopAccent,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Bottom Minimal Bar: ••• Options | Compact Tools Pill | Audio Output ((•))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onOpenOptionsMenu()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = "More options",
                        tint = primaryInk,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Subtle center pill housing Audio Output, Repeat, Sleep Timer, and Up-Next Queue
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onOpenAudioOutputSwitcher()
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("audio_output_route_button")
                        ) {
                            Icon(
                                imageVector = if (audioRouteInfo.name.contains("Speaker", ignoreCase = true) ||
                                    audioRouteInfo.name.contains("Phone", ignoreCase = true)
                                ) Icons.Default.Podcasts else audioRouteInfo.icon,
                                contentDescription = "Audio Output Device",
                                tint = primaryInk.copy(alpha = 0.92f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        val isRepeatActive = playbackState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF
                        IconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onToggleRepeat()
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = if (playbackState.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat",
                                tint = if (isRepeatActive) harmonizedTopAccent else primaryInk.copy(alpha = 0.55f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onOpenSleepTimer()
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = "Sleep Timer",
                                tint = primaryInk.copy(alpha = 0.92f),
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onOpenQueueSheet()
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = "Up Next Queue",
                                tint = primaryInk.copy(alpha = 0.92f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onOpenLyricsSheet()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lyrics,
                        contentDescription = "Lyrics",
                        tint = primaryInk,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * Shapes extracted Neon Studio gradient stops into rich, moody-pastel studio tones
 * so pure white controls, waveforms, and typography always remain crisp and effortless to read.
 */
private fun Color.ensureNeonPastelStage(minLuminance: Float = 0.14f, maxLuminance: Float = 0.36f): Color {
    var current = this
    var steps = 0
    while (current.luminance() > maxLuminance && steps < 8) {
        current = androidx.compose.ui.graphics.lerp(current, Color(0xFF14121E), 0.22f)
        steps++
    }
    steps = 0
    while (current.luminance() < minLuminance && steps < 6) {
        current = androidx.compose.ui.graphics.lerp(current, Color(0xFF4A4460), 0.20f)
        steps++
    }
    return current
}

private fun Color.ensureNeonDarkStage(): Color {
    var current = this.toRichDarkAccent(preserveExactIfAlreadyDark = true)
    var steps = 0
    while (current.luminance() > 0.16f && steps < 8) {
        current = androidx.compose.ui.graphics.lerp(current, Color(0xFF07070B), 0.26f)
        steps++
    }
    steps = 0
    while (current.luminance() < 0.045f && steps < 5) {
        current = androidx.compose.ui.graphics.lerp(current, Color(0xFF221F2D), 0.20f)
        steps++
    }
    return current
}
