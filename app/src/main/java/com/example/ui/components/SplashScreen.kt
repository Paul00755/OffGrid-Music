package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onDismiss: () -> Unit
) {
    var animationStarted by remember { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }
    var isDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        animationStarted = true
        // Let the startup animation play cleanly first before transitioning to Onboarding or Home
        delay(950L)
        if (!isDismissed) {
            isExiting = true
            delay(220L)
            if (!isDismissed) {
                isDismissed = true
                onDismiss()
            }
        }
    }

    val exitAlpha by animateFloatAsState(
        targetValue = if (isExiting) 0f else 1f,
        animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing),
        label = "splashExitAlpha"
    )

    val scaleAnimate by animateFloatAsState(
        targetValue = when {
            isExiting -> 1.04f
            animationStarted -> 1f
            else -> 0.85f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "splashScale"
    )

    val alphaAnimate by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = LinearOutSlowInEasing),
        label = "splashAlpha"
    )

    val textAlphaAnimate by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 260, delayMillis = 80, easing = LinearOutSlowInEasing),
        label = "splashTextAlpha"
    )

    val sloganAlphaAnimate by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 260, delayMillis = 150, easing = FastOutSlowInEasing),
        label = "splashSloganAlpha"
    )

    val sloganTranslationY by animateFloatAsState(
        targetValue = if (animationStarted) 0f else 12f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "splashSloganY"
    )

    // Infinite breathing glow animation for neon rings
    val infiniteTransition = rememberInfiniteTransition(label = "neonPulse")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    val combinedAlpha = (alphaAnimate * exitAlpha).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF07070B),
                        Color(0xFF0F0B18),
                        Color(0xFF07070B)
                    )
                )
            )
            .alpha(exitAlpha.coerceIn(0f, 1f))
            .then(
                if (!isDismissed) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (!isDismissed) {
                            isDismissed = true
                            onDismiss()
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // Ambient Neon Glow Orbs in Background
        Box(
            modifier = Modifier
                .size(280.dp)
                .scale(scaleAnimate)
                .alpha((combinedAlpha * 0.35f * glowPulse).coerceIn(0f, 1f))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF00D2FF).copy(alpha = 0.55f),
                            Color(0xFF9D4EDD).copy(alpha = 0.28f),
                            Color(0xFF9D4EDD).copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(24.dp)
                .scale(scaleAnimate)
                .alpha(combinedAlpha)
        ) {
            // OffGrid Canvas Custom Logo Symbol with Neon Rings & Soundwave
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(170.dp)
            ) {
                // Outer Pulsing Neon Glow Ring
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f - 12.dp.toPx()
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color(0xFF00E5FF),
                                Color(0xFFD500F9),
                                Color(0xFFFF2A85),
                                Color(0xFF00E5FF)
                            )
                        ),
                        radius = radius + 6.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx() * glowPulse),
                        alpha = (0.4f * glowPulse).coerceIn(0f, 1f)
                    )
                }

                // Vector Icon Foreground
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "OffGrid Music Logo",
                    modifier = Modifier
                        .size(160.dp)
                        .scale(1.15f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main Brand Title "OffGrid"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha((textAlphaAnimate * exitAlpha).coerceIn(0f, 1f))
            ) {
                Text(
                    text = "OffGrid",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = 1.5.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Subtitle "— MUSIC —" with cyan and magenta accent lines matching logo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(2.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Color(0xFF00E5FF))
                                )
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "M U S I C",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD8D8E0),
                        letterSpacing = 4.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(2.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFFFF2A85), Color.Transparent)
                                )
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Slogan / Message: "Your Music. Your Way."
            Box(
                modifier = Modifier
                    .offset(y = sloganTranslationY.dp)
                    .alpha((sloganAlphaAnimate * exitAlpha).coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Your Music. Your Way.",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f),
                    letterSpacing = 1.sp
                )
            }
        }
    }
}