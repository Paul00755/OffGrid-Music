package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
fun ScreenLayoutPreviewCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.5f),
        label = "previewBorder"
    )
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = BentoCardSurface,
        border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
        modifier = modifier
            .width(185.dp)
            .height(220.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Miniature UI Layout Mockup Screen Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BentoBackground)
                    .border(0.75.dp, BentoBorder.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            ) {
                content()

                // Selected Checkmark Badge
                if (isSelected) {
                    Surface(
                        shape = CircleShape,
                        color = BentoPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Active",
                            tint = Color.Black,
                            modifier = Modifier.padding(3.dp)
                        )
                    }
                }
            }

            // Description Label & Tag
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) BentoPrimary else BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isSelected) {
                        Text(
                            text = "ACTIVE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = BentoPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = BentoTextSecondary,
                    maxLines = 2,
                    lineHeight = 13.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// -------------------------------------------------------------
// NOW PLAYING MINIATURE PREVIEWS
// -------------------------------------------------------------

@Composable
fun NowPlayingMiniatureModernBento() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top status pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f))
            )
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(BentoPrimary.copy(alpha = 0.4f)))
        }

        // Center Album Artwork Card
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        listOf(BentoPrimary.copy(alpha = 0.8f), BentoCardDarkPurple)
                    )
                )
                .border(0.5.dp, BentoPrimary.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // Song Title & Waveform
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(BentoTextSecondary)
            )
            // Mini Waveform Seeker
            Row(
                modifier = Modifier.fillMaxWidth().height(10.dp),
                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val heights = listOf(3, 6, 8, 5, 9, 10, 7, 4, 8, 9, 5, 7, 10, 6, 4, 7, 8, 3, 5, 6)
                heights.forEachIndexed { i, h ->
                    val color = if (i < 10) BentoPrimary else Color.White.copy(alpha = 0.25f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(h.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }

        // Playback Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(12.dp)
                )
            }
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
        }
    }
}

@Composable
fun NowPlayingMiniatureVinylTurntable() {
    val infiniteTransition = rememberInfiniteTransition(label = "vinylSpin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinAngle"
    )

    Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
        // Turntable Base Platter
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(86.dp)
                .clip(CircleShape)
                .background(Color(0xFF14141A))
                .border(1.dp, Color(0xFF2E2E38), CircleShape)
        ) {
            // Spinning Vinyl Grooves Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(angle)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2
                // Draw vinyl grooved concentric rings
                drawCircle(color = Color(0xFF1F1F28), radius = radius * 0.92f, style = Stroke(1.5f))
                drawCircle(color = Color(0xFF1A1A22), radius = radius * 0.80f, style = Stroke(1.5f))
                drawCircle(color = Color(0xFF242430), radius = radius * 0.68f, style = Stroke(1.5f))
                drawCircle(color = Color(0xFF1E1E26), radius = radius * 0.55f, style = Stroke(1.5f))
                // Center Vinyl Label
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0xFFFFB74D), Color(0xFFE65100))),
                    radius = radius * 0.36f,
                    center = center
                )
                // Center Spindle Hole
                drawCircle(color = Color(0xFF09090D), radius = radius * 0.08f, center = center)
            }
        }

        // Tonearm / Needle
        Canvas(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(36.dp)
                .padding(top = 4.dp, end = 4.dp)
        ) {
            // Tonearm pivot
            drawCircle(color = Color(0xFFFFD54F), radius = 5f, center = Offset(size.width - 6f, 6f))
            // Tonearm stick
            drawLine(
                color = Color(0xFFFFD54F),
                start = Offset(size.width - 6f, 6f),
                end = Offset(size.width * 0.35f, size.height * 0.75f),
                strokeWidth = 2.5f
            )
            // Needle cartridge
            drawCircle(color = Color.White, radius = 3.5f, center = Offset(size.width * 0.35f, size.height * 0.75f))
        }

        // Bottom Retro Controls Pill
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1E1E28).copy(alpha = 0.95f),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFFFFD54F).copy(alpha = 0.5f)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.9f)
                .height(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFD54F))
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.6f)))
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFFD54F)))
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.6f)))
                }
            }
        }
    }
}

@Composable
fun NowPlayingMiniatureMinimalGlass() {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full Bleed Ambient Gradient Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            BentoPrimary.copy(alpha = 0.65f),
                            BentoAccentPinkText.copy(alpha = 0.4f),
                            Color(0xFF09090D)
                        )
                    )
                )
        )

        // Center Album Art Glow
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.2f))
                .border(0.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // Floating Frosted Glass Control Capsule
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.15f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.88f)
                .height(30.dp)
                .padding(bottom = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(11.dp)
                    )
                }
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)))
            }
        }
    }
}

@Composable
fun NowPlayingMiniatureNeonWaveform() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070F))
            .border(1.dp, Color(0xFF00F5D4).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Tech telemetry Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFF007F).copy(alpha = 0.2f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text("24-BIT / 96kHz", fontSize = 6.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF007F))
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF00F5D4).copy(alpha = 0.2f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text("NEON STUDIO", fontSize = 6.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F5D4))
            }
        }

        // Live Equalizer Spectrum Visualizer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val bars = listOf(14, 28, 38, 20, 44, 32, 18, 48, 36, 24, 40, 30, 16, 26)
            bars.forEachIndexed { i, h ->
                val gradient = if (i % 2 == 0) {
                    listOf(Color(0xFF00F5D4), Color(0xFF00B4D8))
                } else {
                    listOf(Color(0xFFFF007F), Color(0xFF7928CA))
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(h.dp)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(Brush.verticalGradient(gradient))
                )
            }
        }

        // Cyber Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1A1A2E))
                    .border(0.5.dp, Color(0xFF00F5D4), RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFF007F)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1A1A2E))
                    .border(0.5.dp, Color(0xFF00F5D4), RoundedCornerShape(4.dp))
            )
        }
    }
}

// -------------------------------------------------------------
// HOME SCREEN MINIATURE PREVIEWS
// -------------------------------------------------------------

@Composable
fun HomeScreenMiniatureBentoMosaic() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Greeting row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(44.dp).height(5.dp).clip(CircleShape).background(Color.White))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(BentoPrimary))
        }

        // Wide Continue Listening Tile
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = BentoCardDarkPurple,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, BentoPrimary.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().height(26.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(modifier = Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(BentoPrimary))
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth(0.8f).height(3.dp).clip(CircleShape).background(Color.White))
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(modifier = Modifier.fillMaxWidth(0.5f).height(2.dp).clip(CircleShape).background(BentoTextSecondary))
                }
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color.White))
            }
        }

        // 2x2 Quick Shortcuts
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BentoCardSurface)
                    .border(0.5.dp, BentoBorder, RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BentoCardSurface)
                    .border(0.5.dp, BentoBorder, RoundedCornerShape(4.dp))
            )
        }

        // Recently Played Carousel
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(BentoPrimary.copy(alpha = 0.5f)))
            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(BentoAccentPinkText.copy(alpha = 0.5f)))
            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF64B5F6).copy(alpha = 0.5f)))
            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF81C784).copy(alpha = 0.5f)))
        }

        // Stats Card
        Surface(
            shape = RoundedCornerShape(5.dp),
            color = BentoCardSurface,
            modifier = Modifier.fillMaxWidth().height(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.width(32.dp).height(3.dp).clip(CircleShape).background(BentoTextSecondary))
                Box(modifier = Modifier.width(18.dp).height(3.dp).clip(CircleShape).background(BentoPrimary))
            }
        }
    }
}

@Composable
fun HomeScreenMiniatureHeroSpotlight() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Large Top Spotlight Hero Banner (55% height)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            BentoPrimary.copy(alpha = 0.85f),
                            Color(0xFF1E1335)
                        )
                    )
                )
                .padding(6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text("FEATURED", fontSize = 6.sp, fontWeight = FontWeight.Bold, color = BentoPrimary)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Box(modifier = Modifier.width(55.dp).height(5.dp).clip(CircleShape).background(Color.White))
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(modifier = Modifier.width(35.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)))
                    }
                    Box(
                        modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(10.dp))
                    }
                }
            }
        }

        // Horizontal Category Chips
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(modifier = Modifier.width(28.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(BentoPrimary))
            Box(modifier = Modifier.width(24.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.15f)))
            Box(modifier = Modifier.width(24.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.15f)))
        }

        // Magazine List Cards
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(BentoAccentPinkText))
                Box(modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White))
                Box(modifier = Modifier.width(12.dp).height(3.dp).clip(CircleShape).background(BentoTextSecondary))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF00E5FF)))
                Box(modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White))
                Box(modifier = Modifier.width(12.dp).height(3.dp).clip(CircleShape).background(BentoTextSecondary))
            }
        }
    }
}

@Composable
fun HomeScreenMiniatureMinimalFeed() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Minimal Title
        Text("Library Feed", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)

        // Ultra Slim Continue Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(BentoPrimary))
            Box(modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }

        // Clean Single Column Track Items
        repeat(4) { i ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("${i + 1}", fontSize = 7.sp, color = BentoTextSecondary, fontWeight = FontWeight.Bold)
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth(0.85f).height(3.dp).clip(CircleShape).background(Color.White))
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(modifier = Modifier.fillMaxWidth(0.5f).height(2.dp).clip(CircleShape).background(BentoTextSecondary))
                }
                Box(modifier = Modifier.width(14.dp).height(2.dp).clip(CircleShape).background(BentoTextSecondary))
            }
            if (i < 3) {
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(BentoBorder.copy(alpha = 0.5f)))
            }
        }
    }
}

@Composable
fun HomeScreenMiniatureStudioDeck() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090912))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Studio Monitor Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("STUDIO DECK", fontSize = 7.sp, fontWeight = FontWeight.Black, color = BentoPrimary)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF00E676).copy(alpha = 0.2f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text("ACTIVE", fontSize = 5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
            }
        }

        // Live Audio Spectrum Graphic Monitor
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0xFF131322),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, BentoPrimary.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().height(36.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                val heights = listOf(8, 16, 24, 12, 28, 20, 14, 30, 22, 10, 26, 18, 12, 22, 14)
                heights.forEach { h ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(h.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Brush.verticalGradient(listOf(BentoPrimary, Color(0xFF7C4DFF))))
                    )
                }
            }
        }

        // 3-Band Equalizer Sliders graphic
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF161626))
                        .padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier.width(12.dp).height(2.dp).clip(CircleShape).background(BentoPrimary))
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(modifier = Modifier.width(2.dp).height(12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
                }
            }
        }

        // Audio Route Telemetry
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("DAC: 24-bit 96k", fontSize = 6.sp, color = BentoTextSecondary)
            Text("EQ: Graph Pro", fontSize = 6.sp, color = BentoPrimary)
        }
    }
}

// -------------------------------------------------------------
// PLAYLISTS SCREEN MINIATURE PREVIEWS
// -------------------------------------------------------------

@Composable
fun PlaylistsMiniatureBentoGrid() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(42.dp).height(4.dp).clip(CircleShape).background(Color.White))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(BentoPrimary))
        }

        // 2x2 Grid of Bento Playlist Cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Card 1
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = BentoCardSurface,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, BentoBorder),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(3.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(3.dp)).background(BentoPrimary))
                    Box(modifier = Modifier.fillMaxWidth(0.75f).height(3.dp).clip(CircleShape).background(Color.White))
                    Box(modifier = Modifier.width(18.dp).height(2.dp).clip(CircleShape).background(BentoTextSecondary))
                }
            }
            // Card 2
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = BentoCardSurface,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, BentoBorder),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(3.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(3.dp)).background(BentoAccentPinkText))
                    Box(modifier = Modifier.fillMaxWidth(0.75f).height(3.dp).clip(CircleShape).background(Color.White))
                    Box(modifier = Modifier.width(18.dp).height(2.dp).clip(CircleShape).background(BentoTextSecondary))
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Card 3
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = BentoCardSurface,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, BentoBorder),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(3.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF00E5FF)))
                    Box(modifier = Modifier.fillMaxWidth(0.75f).height(3.dp).clip(CircleShape).background(Color.White))
                    Box(modifier = Modifier.width(18.dp).height(2.dp).clip(CircleShape).background(BentoTextSecondary))
                }
            }
            // Card 4
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = BentoCardSurface,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, BentoBorder),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(3.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFFF9100)))
                    Box(modifier = Modifier.fillMaxWidth(0.75f).height(3.dp).clip(CircleShape).background(Color.White))
                    Box(modifier = Modifier.width(18.dp).height(2.dp).clip(CircleShape).background(BentoTextSecondary))
                }
            }
        }
    }
}

@Composable
fun PlaylistsMiniatureMagazineHero() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Featured Hero Playlist Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(BentoPrimary, Color(0xFF673AB7))
                    )
                )
                .padding(6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text("TOP PLAYLIST", fontSize = 6.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Box(modifier = Modifier.width(50.dp).height(4.dp).clip(CircleShape).background(Color.White))
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(modifier = Modifier.width(28.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)))
                    }
                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(Color.White))
                }
            }
        }

        // Horizontal Editorial Cards
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = BentoCardSurface,
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Column(modifier = Modifier.padding(3.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(modifier = Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(2.dp)).background(BentoAccentPinkText))
                    Box(modifier = Modifier.fillMaxWidth(0.7f).height(3.dp).clip(CircleShape).background(Color.White))
                }
            }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = BentoCardSurface,
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Column(modifier = Modifier.padding(3.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(modifier = Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF00E5FF)))
                    Box(modifier = Modifier.fillMaxWidth(0.7f).height(3.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
    }
}

@Composable
fun PlaylistsMiniatureCompactList() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(42.dp).height(4.dp).clip(CircleShape).background(Color.White))
            Box(modifier = Modifier.width(20.dp).height(4.dp).clip(CircleShape).background(BentoPrimary))
        }

        repeat(4) { i ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = BentoCardSurface,
                modifier = Modifier.fillMaxWidth().height(22.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when (i) {
                                    0 -> BentoPrimary
                                    1 -> BentoAccentPinkText
                                    2 -> Color(0xFF00E5FF)
                                    else -> Color(0xFFFF9100)
                                }
                            )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.fillMaxWidth(0.7f).height(3.dp).clip(CircleShape).background(Color.White))
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(modifier = Modifier.fillMaxWidth(0.4f).height(2.dp).clip(CircleShape).background(BentoTextSecondary))
                    }
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
                }
            }
        }
    }
}

@Composable
fun PlaylistsMiniatureGlowCards() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NEON DECK", fontSize = 7.sp, fontWeight = FontWeight.Black, color = Color(0xFF00F5D4))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF007F)))
        }

        // 2 Glowing frosted cards
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF131124),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F5D4)),
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF00F5D4), Color(0xFF0066FF))))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth(0.8f).height(4.dp).clip(CircleShape).background(Color.White))
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(modifier = Modifier.fillMaxWidth(0.45f).height(3.dp).clip(CircleShape).background(Color(0xFF00F5D4)))
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF131124),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF007F)),
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFFF007F), Color(0xFF7928CA))))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth(0.8f).height(4.dp).clip(CircleShape).background(Color.White))
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(modifier = Modifier.fillMaxWidth(0.45f).height(3.dp).clip(CircleShape).background(Color(0xFFFF007F)))
                }
            }
        }
    }
}
