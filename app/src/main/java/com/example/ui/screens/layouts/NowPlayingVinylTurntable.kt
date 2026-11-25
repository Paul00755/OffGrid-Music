package com.example.ui.screens.layouts

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.example.data.model.Lyrics
import com.example.service.PlaybackState
import com.example.ui.components.AdvancedSongInfoRow
import com.example.ui.components.AlbumArtGestureBox
import com.example.ui.components.AlbumArtView
import com.example.ui.components.AnimatedFavoriteIcon
import com.example.ui.components.NowPlayingVolumeBar
import com.example.ui.components.NowPlayingWaveformVisualizer
import com.example.ui.components.PlaybackDurationToggleText
import com.example.ui.components.PlaybackPositionText
import com.example.ui.components.SeekIntervalButton
import com.example.ui.theme.*
import com.example.util.ArtworkColorExtractor
import com.example.util.AudioRouteInfo
import com.example.util.AudioTechnicalInfo
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
    val glassConfig = LocalGlassThemeConfig.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // Extract atmospheric dual-tone palette from current album artwork for warm hi-fi vinyl lighting
    val fallbackAccent = BentoPrimary
    val fallbackSecondary = lerp(fallbackAccent, Color(0xFF141218), 0.55f)
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
            val pair = ArtworkColorExtractor.extractDualGradientColorsForSong(context, song)
            if (pair != null) {
                extractedPair = pair
            }
        } else {
            extractedPair = null
        }
    }

    val targetPrimaryTint = if (glassConfig.isDynamicArtworkEnabled && extractedPair != null) {
        extractedPair!!.first
    } else {
        fallbackAccent
    }
    val targetSecondaryTint = if (glassConfig.isDynamicArtworkEnabled && extractedPair != null) {
        extractedPair!!.second
    } else {
        fallbackSecondary
    }

    val animatedPrimaryAccent by animateColorAsState(
        targetValue = targetPrimaryTint,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "vinyl_primary_accent"
    )
    val animatedSecondaryAccent by animateColorAsState(
        targetValue = targetSecondaryTint,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "vinyl_secondary_accent"
    )

    // Smooth continuous turntable spin that preserves its exact resting angle on pause (never snaps to 0°)
    var vinylRotationDeg by remember(song.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(playbackState.isPlaying, song.id) {
        if (!playbackState.isPlaying) return@LaunchedEffect
        var lastFrameNanos = 0L
        val degreesPerSecond = 360f / 4.2f // Authentic smooth visual RPM
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val deltaSec = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    vinylRotationDeg = (vinylRotationDeg + deltaSec * degreesPerSecond) % 360f
                }
                lastFrameNanos = frameNanos
            }
        }
    }

    // Tonearm smoothly tracks onto the vinyl record when playing and parks cleanly off the platter when paused
    val tonearmAngle by animateFloatAsState(
        targetValue = if (playbackState.isPlaying) 23.5f else 0f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessLow
        ),
        label = "vinyl_tonearm_angle"
    )

    val duration = (if (playbackState.durationMs > 0L) playbackState.durationMs else song.durationMs).coerceAtLeast(1L)

    // Atmospheric palette for audiophile dark-stage aesthetic
    val deepStageBase = if (isDarkTheme) Color(0xFF0A0A0E) else lerp(BentoBackground, Color(0xFF141319), 0.90f)
    val inkPrimary = Color(0xFFF6F6FA)
    val inkSecondary = Color(0xFFF6F6FA).copy(alpha = 0.68f)
    val inkSubtle = Color(0xFFF6F6FA).copy(alpha = 0.44f)
    val frostedSurface = Color.White.copy(alpha = 0.08f)
    val frostedBorder = Color.White.copy(alpha = 0.14f)
    val luminousAccent = lerp(animatedPrimaryAccent, Color.White, 0.22f)
    val playButtonContentColor = if (luminousAccent.luminance() > 0.52f) Color(0xFF0E0E12) else Color.White

    val isDenseLayout = showSeekButtons && showVolumeBar

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(deepStageBase)
    ) {
        // Soft studio-lit radial vinyl aura behind the platter
        Canvas(modifier = Modifier.fillMaxSize()) {
            val topAuraCenter = Offset(size.width * 0.5f, size.height * 0.34f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedPrimaryAccent.copy(alpha = 0.36f),
                        animatedSecondaryAccent.copy(alpha = 0.18f),
                        Color.Transparent
                    ),
                    center = topAuraCenter,
                    radius = size.maxDimension * 0.62f
                ),
                radius = size.maxDimension * 0.62f,
                center = topAuraCenter
            )

            // Subtle warm bottom edge vignette blending into controls
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.24f),
                    0.45f to Color.Transparent,
                    0.78f to deepStageBase.copy(alpha = 0.72f),
                    1.0f to deepStageBase.copy(alpha = 0.96f)
                )
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = if (isDenseLayout) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isDenseLayout) 8.dp else 12.dp)
        ) {
            // 1. Minimal Audiophile Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismiss()
                    },
                    shape = CircleShape,
                    color = frostedSurface,
                    border = BorderStroke(1.dp, frostedBorder),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("vinyl_dismiss_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Collapse Now Playing",
                            tint = inkPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Floating live status + output device pill
                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onOpenAudioOutputSwitcher()
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = frostedSurface,
                    border = BorderStroke(1.dp, frostedBorder),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 10.dp)
                        .heightIn(min = 42.dp)
                        .testTag("vinyl_audio_output_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Subtle glowing status LED
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (playbackState.isPlaying) luminousAccent else inkSubtle
                                )
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (playbackState.isPlaying) "33⅓ RPM · SPINNING" else "33⅓ RPM · STANDBY",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                color = if (playbackState.isPlaying) luminousAccent else inkSubtle,
                                maxLines = 1
                            )
                            Text(
                                text = audioRouteInfo.name,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = inkPrimary.copy(alpha = 0.88f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onOpenQueueSheet()
                        },
                        shape = CircleShape,
                        color = frostedSurface,
                        border = BorderStroke(1.dp, frostedBorder),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("vinyl_queue_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Up-Next Queue",
                                tint = inkPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onOpenOptionsMenu()
                        },
                        shape = CircleShape,
                        color = frostedSurface,
                        border = BorderStroke(1.dp, frostedBorder),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("vinyl_options_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options",
                                tint = inkPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 2. Hero Floating 12-Inch Vinyl Turntable Stage (adapts cleanly to any viewport & active toggles)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val stageSize = minOf(maxWidth * 0.96f, maxHeight * 0.96f, 340.dp).coerceAtLeast(150.dp)
                val platterSize = stageSize * 0.88f
                val centerLabelSize = platterSize * 0.43f

                AlbumArtGestureBox(
                    enabled = gesturePlaybackEnabled,
                    isPlaying = playbackState.isPlaying,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipPrevious = onSkipPrevious,
                    onSkipNext = onSkipNext,
                    modifier = Modifier.size(stageSize)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Ambient shadow & illuminated turntable platter ring
                        Canvas(
                            modifier = Modifier
                                .size(stageSize)
                                .align(Alignment.Center)
                        ) {
                            val cx = size.width * 0.47f
                            val cy = size.height * 0.52f
                            val platterRadius = (platterSize.toPx() / 2f)

                            // Soft backlit ambient halo beneath the platter
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        animatedPrimaryAccent.copy(alpha = if (playbackState.isPlaying) 0.42f else 0.22f),
                                        animatedPrimaryAccent.copy(alpha = 0.08f),
                                        Color.Transparent
                                    ),
                                    center = Offset(cx, cy),
                                    radius = platterRadius * 1.24f
                                ),
                                radius = platterRadius * 1.24f,
                                center = Offset(cx, cy)
                            )

                            // Deep elevation drop shadow beneath platter
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.78f),
                                        Color.Black.copy(alpha = 0.35f),
                                        Color.Transparent
                                    ),
                                    center = Offset(cx, cy + platterRadius * 0.06f),
                                    radius = platterRadius * 1.09f
                                ),
                                radius = platterRadius * 1.09f,
                                center = Offset(cx, cy + platterRadius * 0.06f)
                            )

                            // Machined brushed dark aluminum platter outer collar
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color(0xFF3E4049),
                                        Color(0xFF1F2026),
                                        Color(0xFF565966),
                                        Color(0xFF1B1C22),
                                        Color(0xFF42444E),
                                        Color(0xFF3E4049)
                                    ),
                                    center = Offset(cx, cy)
                                ),
                                radius = platterRadius * 1.035f,
                                center = Offset(cx, cy)
                            )

                            // Crisp precision strobe dots around outer metallic bevel
                            val strobeDots = 60
                            val dotRingR = platterRadius * 1.012f
                            for (i in 0 until strobeDots) {
                                val rad = Math.toRadians(i * (360.0 / strobeDots))
                                val dx = cx + (cos(rad) * dotRingR).toFloat()
                                val dy = cy + (sin(rad) * dotRingR).toFloat()
                                val isMajor = i % 5 == 0
                                drawCircle(
                                    color = if (isMajor) luminousAccent.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.16f),
                                    radius = if (isMajor) 1.5.dp.toPx() else 0.9.dp.toPx(),
                                    center = Offset(dx, dy)
                                )
                            }
                        }

                        // Spinning 12-inch Obsidian Vinyl Record + Center Album Label
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(x = -(stageSize * 0.03f), y = stageSize * 0.02f)
                                .size(platterSize)
                                .graphicsLayer { rotationZ = vinylRotationDeg },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val r = size.minDimension / 2f
                                val center = Offset(size.width / 2f, size.height / 2f)

                                // Deep obsidian vinyl disc base
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFF16161B),
                                            Color(0xFF0A0A0D),
                                            Color(0xFF121216),
                                            Color(0xFF070709)
                                        ),
                                        center = center,
                                        radius = r
                                    ),
                                    radius = r,
                                    center = center
                                )

                                // Multi-band realistic micro-grooves with subtle track spacing
                                val grooveRatios = listOf(
                                    0.95f, 0.92f, 0.89f, 0.86f, 0.83f, 0.80f,
                                    0.76f, 0.73f, 0.70f, 0.67f, 0.64f,
                                    0.60f, 0.57f, 0.54f, 0.51f
                                )
                                grooveRatios.forEachIndexed { idx, ratio ->
                                    val isTrackBandGap = idx == 5 || idx == 10
                                    drawCircle(
                                        color = if (isTrackBandGap) {
                                            Color.White.copy(alpha = 0.095f)
                                        } else {
                                            Color.White.copy(alpha = if (idx % 2 == 0) 0.052f else 0.028f)
                                        },
                                        radius = r * ratio,
                                        center = center,
                                        style = Stroke(width = if (isTrackBandGap) 1.1.dp.toPx() else 0.65.dp.toPx())
                                    )
                                }

                                // Run-out groove & lead-in beveled edge highlight
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.14f),
                                    radius = r * 0.985f,
                                    center = center,
                                    style = Stroke(width = 1.dp.toPx())
                                )
                                drawCircle(
                                    color = luminousAccent.copy(alpha = 0.16f),
                                    radius = r * 0.475f,
                                    center = center,
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }

                            // Centered Circular Album Art Record Label
                            Box(
                                modifier = Modifier
                                    .size(centerLabelSize)
                                    .shadow(elevation = 8.dp, shape = CircleShape)
                                    .clip(CircleShape)
                                    .border(2.dp, Color(0xFF1E1E24), CircleShape)
                                    .border(0.75.dp, Color.White.copy(alpha = 0.24f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                AlbumArtView(
                                    song = song,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    cornerRadius = 999,
                                    forceHighResOnline = true
                                )

                                // Center machined metallic spindle hole
                                Canvas(modifier = Modifier.size(16.dp)) {
                                    val c = Offset(size.width / 2f, size.height / 2f)
                                    drawCircle(
                                        color = Color(0xFF141418),
                                        radius = size.minDimension / 2f,
                                        center = c
                                    )
                                    drawCircle(
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color(0xFFE5E7EE), Color(0xFF5A5D68))
                                        ),
                                        radius = size.minDimension * 0.34f,
                                        center = c
                                    )
                                    drawCircle(
                                        color = Color(0xFF0A0A0D),
                                        radius = size.minDimension * 0.14f,
                                        center = c
                                    )
                                }
                            }
                        }

                        // Non-rotating anisotropic vinyl specular light reflections (studio light stays fixed as record spins!)
                        Canvas(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(x = -(stageSize * 0.03f), y = stageSize * 0.02f)
                                .size(platterSize)
                        ) {
                            val r = size.minDimension / 2f
                            val center = Offset(size.width / 2f, size.height / 2f)

                            // Dual opposing diagonal light wedges across vinyl grooves
                            drawArc(
                                brush = Brush.sweepGradient(
                                    0.0f to Color.Transparent,
                                    0.06f to Color.White.copy(alpha = 0.13f),
                                    0.13f to Color.Transparent,
                                    0.50f to Color.Transparent,
                                    0.56f to Color.White.copy(alpha = 0.11f),
                                    0.63f to Color.Transparent,
                                    1.0f to Color.Transparent,
                                    center = center
                                ),
                                startAngle = -55f,
                                sweepAngle = 360f,
                                useCenter = true,
                                topLeft = Offset(center.x - r * 0.96f, center.y - r * 0.96f),
                                size = Size(r * 1.92f, r * 1.92f)
                            )
                            // Mask out center label so specular glint only shines on vinyl grooves
                            drawCircle(
                                color = Color.Transparent,
                                radius = r * 0.44f,
                                center = center
                            )
                        }

                        // Sculpted S-Shaped Audiophile Tonearm Assembly (top-right pivot tracking gracefully onto grooves)
                        Canvas(modifier = Modifier.size(stageSize)) {
                            val pivotX = size.width * 0.875f
                            val pivotY = size.height * 0.155f
                            val pivot = Offset(pivotX, pivotY)
                            val gimbalRadius = size.minDimension * 0.072f

                            // Recessed dark metallic tonearm base plate
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0xFF3A3C46), Color(0xFF181920)),
                                    center = pivot,
                                    radius = gimbalRadius * 1.25f
                                ),
                                radius = gimbalRadius * 1.25f,
                                center = pivot
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.18f),
                                radius = gimbalRadius * 1.25f,
                                center = pivot,
                                style = Stroke(width = 1.dp.toPx())
                            )

                            rotate(degrees = tonearmAngle, pivot = pivot) {
                                // Rear counterweight barrel behind the pivot
                                val counterStart = Offset(pivotX + gimbalRadius * 0.15f, pivotY - gimbalRadius * 1.25f)
                                val counterEnd = Offset(pivotX + gimbalRadius * 0.04f, pivotY - gimbalRadius * 0.35f)
                                drawLine(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF7C808E), Color(0xFF2B2D35)),
                                        start = counterStart,
                                        end = counterEnd
                                    ),
                                    start = counterStart,
                                    end = counterEnd,
                                    strokeWidth = gimbalRadius * 0.72f,
                                    cap = StrokeCap.Butt
                                )

                                // Graceful S-shaped curved metallic tonearm path
                                val armLen = size.height * 0.56f
                                val armPath = Path().apply {
                                    moveTo(pivotX, pivotY)
                                    cubicTo(
                                        pivotX - size.width * 0.015f, pivotY + armLen * 0.34f,
                                        pivotX + size.width * 0.025f, pivotY + armLen * 0.66f,
                                        pivotX - size.width * 0.095f, pivotY + armLen
                                    )
                                }

                                // Soft arm shadow
                                drawPath(
                                    path = armPath,
                                    color = Color.Black.copy(alpha = 0.45f),
                                    style = Stroke(
                                        width = 5.5.dp.toPx(),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                                // Brushed titanium arm shaft
                                drawPath(
                                    path = armPath,
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFFEAECF3), Color(0xFF9EA2B0), Color(0xFFDCE0EA)),
                                        start = Offset(pivotX, pivotY),
                                        end = Offset(pivotX - size.width * 0.095f, pivotY + armLen)
                                    ),
                                    style = Stroke(
                                        width = 3.4.dp.toPx(),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )

                                // Angled audiophile headshell & stylus cartridge
                                val tipX = pivotX - size.width * 0.095f
                                val tipY = pivotY + armLen
                                val cartridgeEndX = tipX - size.width * 0.042f
                                val cartridgeEndY = tipY + size.height * 0.048f

                                drawLine(
                                    color = Color(0xFF23242C),
                                    start = Offset(tipX, tipY),
                                    end = Offset(cartridgeEndX, cartridgeEndY),
                                    strokeWidth = 9.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color.White.copy(alpha = 0.32f),
                                    start = Offset(tipX, tipY),
                                    end = Offset(cartridgeEndX, cartridgeEndY),
                                    strokeWidth = 1.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                // Glowing stylus tracking dot
                                drawCircle(
                                    color = if (playbackState.isPlaying) luminousAccent else inkSubtle,
                                    radius = 2.6.dp.toPx(),
                                    center = Offset(cartridgeEndX, cartridgeEndY)
                                )
                            }

                            // Gimbal top bearing cap
                            drawCircle(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFFCFD3DF), Color(0xFF454854))
                                ),
                                radius = gimbalRadius * 0.56f,
                                center = pivot
                            )
                            drawCircle(
                                color = Color(0xFF181920),
                                radius = gimbalRadius * 0.24f,
                                center = pivot
                            )
                        }
                    }
                }
            }

            // 3. Borderless Integrated Track Info + Waveform Progress (no clunky box border)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (isDenseLayout) 6.dp else 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = song.displayTitle,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val artistSubtitle = remember(song.displayArtist, song.album) {
                            val albumPart = song.album.takeIf {
                                it.isNotBlank() && !it.equals("Unknown Album", ignoreCase = true)
                            }
                            if (albumPart != null) "${song.displayArtist} · $albumPart" else song.displayArtist
                        }
                        Text(
                            text = artistSubtitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (showAdvancedSongInfo && audioTechnicalInfo != null) {
                            AdvancedSongInfoRow(
                                info = audioTechnicalInfo,
                                inkColor = inkSecondary,
                                centered = false,
                                usePillSurface = false
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onFavoriteToggle()
                        },
                        shape = CircleShape,
                        color = if (song.isFavorite) BentoAccentPinkText.copy(alpha = 0.22f) else frostedSurface,
                        border = BorderStroke(
                            1.dp,
                            if (song.isFavorite) BentoAccentPinkText.copy(alpha = 0.5f) else frostedBorder
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("vinyl_favorite_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AnimatedFavoriteIcon(
                                isFavorite = song.isFavorite,
                                activeTint = BentoAccentPinkText,
                                inactiveTint = inkPrimary,
                                size = 21.dp,
                                contentDescription = "Favorite",
                                animationKey = song.id
                            )
                        }
                    }
                }

                // Waveform Scrubber + Timestamps
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    NowPlayingWaveformVisualizer(
                        trackId = song.id,
                        progressMs = positionMs,
                        durationMs = duration,
                        isPlaying = playbackState.isPlaying,
                        style = waveformStyle,
                        accentColor = luminousAccent,
                        onSeekTo = onSeekTo,
                        playbackPositionFlow = playbackPositionFlow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isDenseLayout) 30.dp else 36.dp)
                            .testTag("vinyl_waveform_visualizer")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlaybackPositionText(
                            playbackPositionFlow = playbackPositionFlow,
                            positionMs = positionMs,
                            durationMs = duration,
                            fontSize = 11.5.sp,
                            color = inkSecondary
                        )
                        PlaybackDurationToggleText(
                            playbackPositionFlow = playbackPositionFlow,
                            positionMs = positionMs,
                            durationMs = duration,
                            showRemainingTime = showRemainingTime,
                            onToggleShowRemainingTime = onToggleShowRemainingTime,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = inkSecondary
                        )
                    }
                }
            }

            // 4. Refined Tactile Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isShuffleActive = playbackState.isShuffleEnabled
                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onToggleShuffle()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("vinyl_shuffle_button")
                ) {
                    Icon(
                        imageVector = if (isShuffleActive) Icons.Default.Shuffle else Icons.Outlined.Shuffle,
                        contentDescription = if (isShuffleActive) "Shuffle on" else "Shuffle off",
                        tint = if (isShuffleActive) luminousAccent else inkSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onSkipPrevious()
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("vinyl_previous_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = inkPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Elevated luminous Play/Pause centerpiece
                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onTogglePlayPause()
                    },
                    shape = CircleShape,
                    color = luminousAccent,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .size(if (isDenseLayout) 62.dp else 68.dp)
                        .testTag("vinyl_play_pause_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = playButtonContentColor,
                            modifier = Modifier.size(if (isDenseLayout) 32.dp else 36.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onSkipNext()
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("vinyl_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = inkPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onToggleRepeat()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("vinyl_repeat_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) luminousAccent else inkSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 5. Optional Forward / Backward Seek Interval Controls (5s / 10s / 15s / 30s)
            if (showSeekButtons) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SeekIntervalButton(
                        seconds = backwardSeconds,
                        isForward = false,
                        onClick = onSeekBackward,
                        tint = inkPrimary,
                        iconSize = 26.dp,
                        touchSize = 48.dp
                    )
                    SeekIntervalButton(
                        seconds = forwardSeconds,
                        isForward = true,
                        onClick = onSeekForward,
                        tint = inkPrimary,
                        iconSize = 26.dp,
                        touchSize = 48.dp
                    )
                }
            }

            // 6. Optional Sleek Volume Bar
            if (showVolumeBar) {
                NowPlayingVolumeBar(
                    inkColor = inkPrimary,
                    accentColor = luminousAccent,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }

            // 7. Compact Bottom Action Pill Strip (Lyrics · Output · Sleep)
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = frostedSurface,
                border = BorderStroke(1.dp, frostedBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VinylBottomUtilityAction(
                        icon = Icons.Default.Lyrics,
                        label = "Lyrics",
                        tint = inkPrimary,
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onOpenLyricsSheet()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vinyl_lyrics_button")
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(frostedBorder)
                    )
                    VinylBottomUtilityAction(
                        icon = audioRouteInfo.icon,
                        label = "Output",
                        tint = inkPrimary,
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onOpenAudioOutputSwitcher()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vinyl_output_button")
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(frostedBorder)
                    )
                    VinylBottomUtilityAction(
                        icon = Icons.Default.Bedtime,
                        label = "Sleep",
                        tint = inkPrimary,
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onOpenSleepTimer()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vinyl_sleep_button")
                    )
                }
            }
        }
    }
}

@Composable
private fun VinylBottomUtilityAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 42.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint.copy(alpha = 0.88f),
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint.copy(alpha = 0.90f),
            maxLines = 1
        )
    }
}
