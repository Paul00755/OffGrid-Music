package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.data.model.AlbumStat
import com.example.data.model.ArtistStat
import com.example.data.model.Song
import com.example.data.model.TimeOfDayDistribution
import com.example.data.model.WrappedData
import com.example.ui.theme.*
import kotlinx.coroutines.delay

private data class WrappedStoryPalette(
    val isDark: Boolean,
    val ink: Color,
    val subInk: Color,
    val mutedInk: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val pillBg: Color,
    val iconBtnBg: Color,
    val progressIndicator: Color,
    val progressTrack: Color,
    val posterCardBg: Color,
    val posterGradient: List<Color>,
    val onAccentPink: Color
)

@Composable
private fun rememberWrappedStoryPalette(): WrappedStoryPalette {
    val isDark = BentoBackground.luminance() < 0.5f
    val onAccentPink = if (BentoAccentPinkText.luminance() > 0.45f) Color.Black else Color.White
    return if (isDark) {
        WrappedStoryPalette(
            isDark = true,
            ink = Color.White,
            subInk = Color.White.copy(alpha = 0.78f),
            mutedInk = Color.White.copy(alpha = 0.55f),
            cardBg = Color.White.copy(alpha = 0.08f),
            cardBorder = Color.White.copy(alpha = 0.16f),
            pillBg = Color.White.copy(alpha = 0.11f),
            iconBtnBg = Color.Black.copy(alpha = 0.40f),
            progressIndicator = Color.White,
            progressTrack = Color.White.copy(alpha = 0.25f),
            posterCardBg = Color(0xFF1B1B26),
            posterGradient = listOf(Color(0xFF281E48), Color(0xFF141420)),
            onAccentPink = onAccentPink
        )
    } else {
        WrappedStoryPalette(
            isDark = false,
            ink = BentoTextPrimary,
            subInk = BentoTextSecondary,
            mutedInk = BentoTextSecondary.copy(alpha = 0.75f),
            cardBg = Color.White.copy(alpha = 0.80f),
            cardBorder = BentoTextPrimary.copy(alpha = 0.10f),
            pillBg = BentoTextPrimary.copy(alpha = 0.07f),
            iconBtnBg = Color.White.copy(alpha = 0.82f),
            progressIndicator = BentoPrimary,
            progressTrack = BentoTextPrimary.copy(alpha = 0.16f),
            posterCardBg = Color.White,
            posterGradient = listOf(
                Color.White,
                androidx.compose.ui.graphics.lerp(Color.White, BentoPrimary, 0.10f)
            ),
            onAccentPink = onAccentPink
        )
    }
}

@Composable
fun WrappedStoryDialog(
    wrappedData: WrappedData,
    timePeriodTitle: String,
    onDismiss: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayTopQueue: (List<Song>) -> Unit,
    loadWrappedData: (suspend (Long, Long) -> WrappedData)? = null,
    initialRange: StoryTimeRange? = null
) {
    var currentSlide by remember { mutableStateOf(0) }
    val totalSlides = 6
    var isPaused by remember { mutableStateOf(false) }
    val slideProgress = remember { Animatable(0f) }
    val context = LocalContext.current
    var showSharePeriodDialog by remember { mutableStateOf(false) }
    var showShareCardDialog by remember { mutableStateOf(false) }

    val defaultRange = remember(initialRange, timePeriodTitle) {
        if (initialRange != null) {
            initialRange
        } else {
            val matching = StoryPeriod.values().find { it.label.equals(timePeriodTitle, ignoreCase = true) }
            if (matching != null) {
                StoryTimeRange.forPeriod(matching)
            } else {
                StoryTimeRange.custom(0L, Long.MAX_VALUE, timePeriodTitle)
            }
        }
    }
    var selectedShareRange by remember { mutableStateOf(defaultRange) }
    var activeWrappedData by remember(wrappedData) { mutableStateOf(wrappedData) }

    LaunchedEffect(defaultRange, loadWrappedData) {
        if (loadWrappedData != null && defaultRange != null) {
            val (start, end) = defaultRange.getTimeRange()
            try {
                val loaded = loadWrappedData(start, end)
                activeWrappedData = loaded
            } catch (_: Exception) {}
        }
    }

    if (showSharePeriodDialog) {
        SharePeriodSelectionDialog(
            selectedRange = selectedShareRange,
            onDismiss = {
                showSharePeriodDialog = false
                isPaused = false
            },
            onRangeSelected = { range ->
                selectedShareRange = range
                showSharePeriodDialog = false
                showShareCardDialog = true
            }
        )
    }

    if (showShareCardDialog) {
        ShareStatsCardDialog(
            wrappedData = activeWrappedData,
            timePeriodTitle = selectedShareRange.displayLabel,
            initialRange = selectedShareRange,
            initialPeriod = selectedShareRange.period,
            loadWrappedData = loadWrappedData,
            onDismiss = {
                showShareCardDialog = false
                isPaused = false
            }
        )
    }

    // Auto progress timer for stories
    LaunchedEffect(currentSlide, isPaused, showShareCardDialog, showSharePeriodDialog) {
        if (!isPaused && !showShareCardDialog && !showSharePeriodDialog) {
            slideProgress.snapTo(0f)
            slideProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 6500, easing = LinearEasing)
            )
            if (currentSlide < totalSlides - 1) {
                currentSlide++
            }
        }
    }

    val palette = rememberWrappedStoryPalette()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val bgGradientColors = if (palette.isDark) {
            listOf(
                when (currentSlide) {
                    0 -> Color(0xFF311B92)
                    1 -> Color(0xFF880E4F)
                    2 -> Color(0xFF004D40)
                    3 -> Color(0xFF1A237E)
                    4 -> Color(0xFF4A148C)
                    else -> Color(0xFF1E1E2E)
                },
                Color(0xFF0D0D14),
                Color(0xFF050508)
            )
        } else {
            val slideAccentWash = when (currentSlide) {
                0 -> Color(0xFFE0D7FF)
                1 -> Color(0xFFFFD8E8)
                2 -> Color(0xFFD0F3EA)
                3 -> Color(0xFFD9E6FF)
                4 -> Color(0xFFEBD7FA)
                else -> Color(0xFFE8ECF8)
            }
            listOf(
                slideAccentWash,
                androidx.compose.ui.graphics.lerp(slideAccentWash, Color(0xFFF8FAFC), 0.58f),
                Color(0xFFF6F7FB)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = bgGradientColors,
                        radius = 1200f
                    )
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPaused = true
                            tryAwaitRelease()
                            isPaused = false
                        },
                        onTap = { offset ->
                            val width = size.width
                            if (offset.x < width * 0.35f) {
                                // Tap left side -> Prev
                                if (currentSlide > 0) currentSlide--
                            } else {
                                // Tap right side -> Next
                                if (currentSlide < totalSlides - 1) {
                                    currentSlide++
                                } else {
                                    onDismiss()
                                }
                            }
                        }
                    )
                }
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Progress Bar & Header
                Column {
                    // Segmented Story Bars
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (i in 0 until totalSlides) {
                            val progress = when {
                                i < currentSlide -> 1f
                                i == currentSlide -> slideProgress.value
                                else -> 0f
                            }
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(CircleShape),
                                color = palette.progressIndicator,
                                trackColor = palette.progressTrack
                            )
                        }
                    }

                    // Header with title and close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = BentoPrimary.copy(alpha = if (palette.isDark) 0.2f else 0.14f),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "OFFGRID WRAPPED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = BentoPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = timePeriodTitle,
                                fontSize = 12.sp,
                                color = palette.subInk,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    isPaused = true
                                    showSharePeriodDialog = true
                                },
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(palette.iconBtnBg)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share Stats Card",
                                    tint = palette.ink,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(palette.iconBtnBg)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = palette.ink,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Slide Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = currentSlide,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (fadeIn(animationSpec = tween(300)) + slideInHorizontally { it / 2 })
                                    .togetherWith(fadeOut(animationSpec = tween(300)) + slideOutHorizontally { -it / 2 })
                            } else {
                                (fadeIn(animationSpec = tween(300)) + slideInHorizontally { -it / 2 })
                                    .togetherWith(fadeOut(animationSpec = tween(300)) + slideOutHorizontally { it / 2 })
                            }
                        },
                        label = "slide_transition"
                    ) { slide ->
                        when (slide) {
                            0 -> SlideMinutesAndArchetype(activeWrappedData, timePeriodTitle, palette)
                            1 -> SlideTopTrack(activeWrappedData, onPlaySong, palette)
                            2 -> SlideTop5Leaderboard(activeWrappedData, onPlaySong, palette)
                            3 -> SlideTopArtistsAlbums(activeWrappedData, palette)
                            4 -> SlideTimeOfDay(activeWrappedData, palette)
                            5 -> SlideSummaryCard(
                                data = activeWrappedData,
                                timePeriodTitle = timePeriodTitle,
                                onPlayTopQueue = onPlayTopQueue,
                                onDismiss = onDismiss,
                                onShareStatsCard = {
                                    isPaused = true
                                    showSharePeriodDialog = true
                                },
                                palette = palette
                            )
                        }
                    }
                }

                // Bottom Hint
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (currentSlide > 0) "◀ Tap left for prev" else "",
                        fontSize = 11.sp,
                        color = palette.mutedInk
                    )
                    Text(
                        text = if (currentSlide < totalSlides - 1) "Tap right for next ▶" else "Finish",
                        fontSize = 11.sp,
                        color = palette.mutedInk
                    )
                }
            }
        }
    }
}

@Composable
private fun SlideMinutesAndArchetype(
    data: WrappedData,
    timePeriodTitle: String,
    palette: WrappedStoryPalette
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )

        Surface(
            shape = CircleShape,
            color = BentoAccentPinkText.copy(alpha = 0.15f),
            border = BorderStroke(2.dp, BentoAccentPinkText.copy(alpha = 0.5f)),
            modifier = Modifier
                .size(130.dp)
                .scale(scale)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = BentoAccentPinkText,
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = BentoAccentPinkText.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, BentoAccentPinkText.copy(alpha = 0.4f)),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = timePeriodTitle.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = BentoAccentPinkText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        Text(
            text = "YOU LISTENED FOR",
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = palette.subInk
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${data.totalMinutes} Minutes",
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold,
            color = palette.ink
        )
        val daysCount = data.activeDaysCount.coerceAtLeast(if (data.totalTracks > 0 || data.totalMinutes > 0) 1 else 0)
        val daysText = if (daysCount == 1) "1 active listening day" else "$daysCount active listening days"
        Text(
            text = "Across $daysText • ${data.totalTracks} total plays",
            fontSize = 13.sp,
            color = palette.subInk,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Archetype Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = palette.cardBg,
            border = BorderStroke(1.dp, palette.cardBorder),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "YOUR LISTENER ARCHETYPE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoPrimary,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = data.listenerArchetype,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = palette.ink
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = data.archetypeDescription,
                    fontSize = 12.sp,
                    color = palette.subInk,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SlideTopTrack(
    data: WrappedData,
    onPlaySong: (Song) -> Unit,
    palette: WrappedStoryPalette
) {
    val song = data.topSong
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing)
        ),
        label = "rotation"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "YOUR #1 ANTHEM",
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = BentoAccentPinkText
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (song != null) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .shadow(16.dp, CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color(0xFF14151E))
                        .border(4.dp, if (palette.isDark) Color(0xFF2B2B38) else Color(0xFF334155), CircleShape)
                        .rotate(rotation)
                ) {
                    AlbumArtView(
                        song = song,
                        modifier = Modifier
                            .size(110.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape),
                        cornerRadius = 55
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = BentoPrimary,
                    modifier = Modifier
                        .size(36.dp)
                        .align(Alignment.TopEnd)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "#1",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = BentoOnPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = song.displayTitle,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song.displayArtist,
                fontSize = 15.sp,
                color = palette.subInk,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.cardBg,
                border = BorderStroke(1.dp, palette.cardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Played ${data.topSongPlayCount} times (${formatListeningDuration(data.topSongDurationMs)})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onPlaySong(song) },
                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = BentoOnPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Play Anthem Now",
                    fontWeight = FontWeight.Bold,
                    color = BentoOnPrimary,
                    fontSize = 14.sp
                )
            }
        } else {
            Text(
                text = "No track plays recorded in this period",
                fontSize = 14.sp,
                color = palette.subInk
            )
        }
    }
}

@Composable
private fun SlideTop5Leaderboard(
    data: WrappedData,
    onPlaySong: (Song) -> Unit,
    palette: WrappedStoryPalette
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "YOUR TOP 5 SONGS",
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = BentoPrimary
        )
        Text(
            text = "The tracks on heavy rotation",
            fontSize = 12.sp,
            color = palette.subInk,
            modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
        )

        val top5 = data.topSongs.take(5)
        if (top5.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                top5.forEachIndexed { index, song ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.cardBg,
                        border = BorderStroke(1.dp, if (index == 0) BentoPrimary.copy(alpha = 0.6f) else palette.cardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaySong(song) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#${index + 1}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = if (index == 0) BentoPrimary else palette.subInk,
                                modifier = Modifier.width(30.dp)
                            )
                            AlbumArtView(
                                song = song,
                                modifier = Modifier.size(42.dp),
                                cornerRadius = 8
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.displayTitle,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = song.displayArtist,
                                    fontSize = 12.sp,
                                    color = palette.subInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (song.playCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = BentoPrimary.copy(alpha = if (palette.isDark) 0.2f else 0.14f)
                                ) {
                                    Text(
                                        text = "${song.playCount} plays",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Text(
                text = "Keep playing songs to unlock your top 5",
                fontSize = 14.sp,
                color = palette.subInk,
                modifier = Modifier.padding(top = 40.dp)
            )
        }
    }
}

@Composable
private fun SlideTopArtistsAlbums(
    data: WrappedData,
    palette: WrappedStoryPalette
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "TOP ARTISTS & ALBUMS",
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = BentoAccentPinkText
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Top Artists Box
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = palette.cardBg,
            border = BorderStroke(1.dp, palette.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = BentoAccentPinkText,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Top Artists",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                val artists = data.topArtists.take(4)
                if (artists.isNotEmpty()) {
                    artists.forEachIndexed { idx, artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}.",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoAccentPinkText,
                                    modifier = Modifier.width(22.dp)
                                )
                                Text(
                                    text = artist.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${artist.playCount} plays",
                                fontSize = 11.sp,
                                color = palette.subInk
                            )
                        }
                    }
                } else {
                    Text(
                        text = "No artist stats yet",
                        fontSize = 12.sp,
                        color = palette.mutedInk
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Top Albums Box
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = palette.cardBg,
            border = BorderStroke(1.dp, palette.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Top Albums",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                val albums = data.topAlbums.take(3)
                if (albums.isNotEmpty()) {
                    albums.forEachIndexed { idx, album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}.",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary,
                                    modifier = Modifier.width(22.dp)
                                )
                                Column {
                                    Text(
                                        text = album.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = album.artist,
                                        fontSize = 11.sp,
                                        color = palette.mutedInk,
                                        maxLines = 1
                                    )
                                }
                            }
                            Text(
                                text = "${album.playCount} plays",
                                fontSize = 11.sp,
                                color = palette.subInk
                            )
                        }
                    }
                } else {
                    Text(
                        text = "No album stats yet",
                        fontSize = 12.sp,
                        color = palette.mutedInk
                    )
                }
            }
        }
    }
}

@Composable
private fun SlideTimeOfDay(
    data: WrappedData,
    palette: WrappedStoryPalette
) {
    val total = data.timeOfDay.totalMs.coerceAtLeast(1L)
    val morningPct = ((data.timeOfDay.morningDurationMs.toFloat() / total) * 100).toInt()
    val afternoonPct = ((data.timeOfDay.afternoonDurationMs.toFloat() / total) * 100).toInt()
    val eveningPct = ((data.timeOfDay.eveningDurationMs.toFloat() / total) * 100).toInt()
    val nightPct = ((data.timeOfDay.nightDurationMs.toFloat() / total) * 100).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "YOUR LISTENING CLOCK",
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = BentoPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Dominant Vibe: ${data.timeOfDay.dominantVibe}",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = palette.ink
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Time slots cards
        Column(
            modifier = Modifier.fillMaxWidth(0.92f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TimeSlotBar(
                title = "Morning (05:00 - 12:00)",
                percentage = morningPct,
                durationFormatted = formatListeningDuration(data.timeOfDay.morningDurationMs),
                icon = Icons.Default.WbSunny,
                color = if (palette.isDark) Color(0xFFFFB74D) else Color(0xFFD97706),
                palette = palette
            )
            TimeSlotBar(
                title = "Afternoon (12:00 - 17:00)",
                percentage = afternoonPct,
                durationFormatted = formatListeningDuration(data.timeOfDay.afternoonDurationMs),
                icon = Icons.Default.LightMode,
                color = if (palette.isDark) Color(0xFF4FC3F7) else Color(0xFF0284C7),
                palette = palette
            )
            TimeSlotBar(
                title = "Evening (17:00 - 22:00)",
                percentage = eveningPct,
                durationFormatted = formatListeningDuration(data.timeOfDay.eveningDurationMs),
                icon = Icons.Default.NightsStay,
                color = if (palette.isDark) Color(0xFFBA68C8) else Color(0xFF9333EA),
                palette = palette
            )
            TimeSlotBar(
                title = "Night Owl (22:00 - 05:00)",
                percentage = nightPct,
                durationFormatted = formatListeningDuration(data.timeOfDay.nightDurationMs),
                icon = Icons.Default.Bedtime,
                color = if (palette.isDark) Color(0xFF7986CB) else Color(0xFF4F46E5),
                palette = palette
            )
        }
    }
}

@Composable
private fun TimeSlotBar(
    title: String,
    percentage: Int,
    durationFormatted: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    palette: WrappedStoryPalette
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink
                    )
                }
                Text(
                    text = "$percentage%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    color = color
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { (percentage / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = color,
                trackColor = palette.progressTrack
            )
        }
    }
}

@Composable
private fun SlideSummaryCard(
    data: WrappedData,
    timePeriodTitle: String,
    onPlayTopQueue: (List<Song>) -> Unit,
    onDismiss: () -> Unit,
    onShareStatsCard: () -> Unit,
    palette: WrappedStoryPalette
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Summary Poster Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = palette.posterCardBg,
            border = BorderStroke(2.dp, BentoPrimary.copy(alpha = if (palette.isDark) 0.8f else 0.55f)),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(if (palette.isDark) 12.dp else 6.dp, RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(colors = palette.posterGradient))
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "OffGrid Music logo",
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "OFFGRID RECAP",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = BentoPrimary
                        )
                    }
                    Text(
                        text = timePeriodTitle,
                        fontSize = 11.sp,
                        color = palette.subInk,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stats Pills Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = palette.pillBg,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("${data.totalMinutes}m", fontSize = 16.sp, fontWeight = FontWeight.Black, color = palette.ink)
                            Text("Minutes", fontSize = 10.sp, color = palette.subInk)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = palette.pillBg,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("${data.totalTracks}", fontSize = 16.sp, fontWeight = FontWeight.Black, color = palette.ink)
                            Text("Plays", fontSize = 10.sp, color = palette.subInk)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = palette.pillBg,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(data.timeOfDay.dominantVibe, fontSize = 13.sp, fontWeight = FontWeight.Black, color = BentoPrimary, maxLines = 1)
                            Text("Vibe", fontSize = 10.sp, color = palette.subInk)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Top Songs List
                Text(
                    text = "TOP SONGS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = BentoAccentPinkText
                )
                Spacer(modifier = Modifier.height(4.dp))

                data.topSongs.take(4).forEachIndexed { idx, song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${idx + 1}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary,
                            modifier = Modifier.width(18.dp)
                        )
                        Text(
                            text = song.displayTitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = song.displayArtist,
                            fontSize = 11.sp,
                            color = palette.mutedInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }

                if (data.topArtists.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "TOP ARTIST: ${data.topArtists.first().name}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (data.topSongs.isNotEmpty()) {
                Button(
                    onClick = {
                        onPlayTopQueue(data.topSongs)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = BentoOnPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play Top Queue", color = BentoOnPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Button(
                onClick = onShareStatsCard,
                modifier = Modifier.weight(1.1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BentoAccentPinkText,
                    contentColor = palette.onAccentPink
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = palette.onAccentPink,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Share My Stats", fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
        }
    }
}
