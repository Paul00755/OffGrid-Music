package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scanner.ScanProgress
import com.example.ui.components.BentoCard
import com.example.ui.theme.*

@Composable
fun OnboardingScreen(
    scanProgress: ScanProgress,
    hasAllFilesAccess: Boolean = false,
    onRequestStoragePermission: () -> Unit = {},
    onStartScan: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStep by remember { mutableStateOf(1) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BentoCardPurple, BentoBackground)
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Progress Dots
            Row(
                modifier = Modifier.padding(top = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 1..3) {
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (i == currentStep) 28.dp else 10.dp)
                            .clip(CircleShape)
                            .background(if (i == currentStep) BentoPrimary else BentoCardBorder)
                    )
                }
            }

            // Step Content Card
            when (currentStep) {
                1 -> {
                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BentoCardDarkPurple,
                        padding = 24.dp
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(BentoPrimaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = BentoOnPrimaryContainer,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            Text(
                                text = "Welcome to OffGrid Music",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = "Your premium offline-first personal music hub. Play high fidelity audio stored locally on your Android device.",
                                fontSize = 14.sp,
                                color = BentoTextSecondary,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
                2 -> {
                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BentoCardDarkPurple,
                        padding = 24.dp
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(BentoPrimaryContainer.copy(alpha = 0.8f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderSpecial,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            Text(
                                text = "Allow Access to All Files",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = "To discover all songs, audiobooks, and downloads across your device without missing folders, OffGrid Music needs Storage & All Files Access.",
                                fontSize = 13.sp,
                                color = BentoTextSecondary,
                                textAlign = TextAlign.Center,
                                lineHeight = 19.sp
                            )

                            if (hasAllFilesAccess) {
                                Surface(
                                    color = Color(0xFF1B5E20),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "All Files Storage Access Granted",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else {
                                Button(
                                    onClick = onRequestStoragePermission,
                                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = BentoOnPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Grant Storage Permission",
                                        color = BentoOnPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Text(
                                text = "Note: By default, all device audio is indexed. If you prefer, you can also select specific included/excluded folders in Settings.",
                                fontSize = 11.sp,
                                color = BentoTextSecondary.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                3 -> {
                    BentoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BentoCardDarkPurple,
                        padding = 24.dp
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(56.dp)
                            )

                            Text(
                                text = "Library Indexing",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Text(
                                text = "Ready to index your local audio files (MP3, FLAC, M4A, WAV, AAC, OGG).",
                                fontSize = 13.sp,
                                color = BentoTextSecondary,
                                textAlign = TextAlign.Center
                            )

                            Button(
                                onClick = onStartScan,
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Start Library Scan", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                            }

                            if (scanProgress.isScanning) {
                                if (scanProgress.totalDiscovered > 0) {
                                    LinearProgressIndicator(
                                        progress = { (scanProgress.processedCount.toFloat() / scanProgress.totalDiscovered.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                        color = BentoPrimary
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                        color = BentoPrimary
                                    )
                                }
                                Text(
                                    text = if (scanProgress.currentFolder.isNotBlank()) {
                                        "Scanning ${scanProgress.currentFolder} (${scanProgress.processedCount} tracks)..."
                                    } else {
                                        "Indexed ${scanProgress.processedCount} tracks..."
                                    },
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            } else if (scanProgress.isFinished) {
                                Text(
                                    text = "✓ Found ${scanProgress.totalDiscovered} audio tracks!",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Navigation Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentStep > 1) {
                    OutlinedButton(
                        onClick = { currentStep-- },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Back", color = BentoTextPrimary)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Button(
                    onClick = {
                        if (currentStep < 3) {
                            currentStep++
                        } else {
                            onComplete()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (currentStep == 3) "Get Started" else "Next",
                        color = BentoOnPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
