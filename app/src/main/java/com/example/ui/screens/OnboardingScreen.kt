package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FolderInfo
import com.example.scanner.ScanProgress
import com.example.ui.components.BentoCard
import com.example.ui.components.HierarchicalFolderBrowserModal
import com.example.ui.theme.*
import kotlin.math.roundToInt

enum class InitialScanMode {
    SPECIFIC_FOLDERS,
    FULL_DEVICE
}

@Composable
fun OnboardingScreen(
    scanProgress: ScanProgress,
    hasAllFilesAccess: Boolean = false,
    folders: List<FolderInfo> = emptyList(),
    ignoreDurationSec: Int = 0,
    maxDurationSec: Int = 300,
    fullDeviceScan: Boolean = false,
    onSetFullDeviceScan: (Boolean) -> Unit = {},
    onSetSongDurationRange: (minSec: Int, maxSec: Int) -> Unit = { _, _ -> },
    onAddFolder: (path: String, name: String, isExcluded: Boolean) -> Unit = { _, _, _ -> },
    onRemoveFolder: (path: String) -> Unit = {},
    onRequestStoragePermission: () -> Unit = {},
    onStartScan: (forceFullScan: Boolean) -> Unit = {},
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStep by remember { mutableIntStateOf(1) }
    var localFullScan by remember(fullDeviceScan) { mutableStateOf(fullDeviceScan) }
    var showFolderBrowserModal by remember { mutableStateOf(false) }
    var localMinSec by remember(ignoreDurationSec) { mutableFloatStateOf(ignoreDurationSec.coerceIn(0, 300).toFloat()) }
    var localMaxSec by remember(maxDurationSec) { mutableFloatStateOf(maxDurationSec.coerceIn(0, 300).toFloat().let { if (it < localMinSec) 300f else it }) }

    val includedFolders = remember(folders) { folders.filter { !it.isExcluded } }
    val excludedFolders = remember(folders) { folders.filter { it.isExcluded } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BentoCardPurple, BentoBackground)
                )
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Progress Dots
            Row(
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
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

            // Step Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
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
                                    text = "Your offline-first music experience. Play high-fidelity audio stored locally on your Android device with zero telemetry and zero ads.",
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
                                    text = "Allow Storage Access",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = "To discover songs, albums, and downloads across your device without missing folders, OffGrid Music requires Storage Access.",
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
                                                text = "Storage Permission Granted",
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
                            }
                        }
                    }
                    3 -> {
                        // Step 3: Library Setup & Folder / Duration Configuration
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Main Bento Card: Library Setup
                            BentoCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = BentoCardDarkPurple,
                                padding = 18.dp
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    // Section Header: Library Setup
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(BentoPrimaryContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tune,
                                                contentDescription = null,
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "Library Setup",
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "Configure scan options, folder filters & duration bounds",
                                                fontSize = 12.sp,
                                                color = BentoTextSecondary
                                            )
                                        }
                                    }

                                    // 1. Full-Device Scan Toggle (Switch)
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        color = BentoCardPurple,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (localFullScan) BentoPrimary.copy(alpha = 0.5f) else BentoCardBorder.copy(alpha = 0.4f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(CircleShape)
                                                        .background(if (localFullScan) BentoPrimaryContainer else BentoCardDarkPurple),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Devices,
                                                        contentDescription = null,
                                                        tint = if (localFullScan) BentoPrimary else BentoTextSecondary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                                Column {
                                                    Text(
                                                        text = "Full-Device Scan",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoTextPrimary
                                                    )
                                                    Text(
                                                        text = if (localFullScan) {
                                                            "Scan entire device partitions automatically"
                                                        } else {
                                                            "Constrained strictly to your selected folders"
                                                        },
                                                        fontSize = 11.5.sp,
                                                        color = BentoTextSecondary
                                                    )
                                                }
                                            }

                                            Switch(
                                                checked = localFullScan,
                                                onCheckedChange = { isChecked ->
                                                    localFullScan = isChecked
                                                    onSetFullDeviceScan(isChecked)
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = BentoPrimary,
                                                    checkedTrackColor = BentoPrimaryContainer,
                                                    uncheckedThumbColor = BentoTextSecondary,
                                                    uncheckedTrackColor = BentoCardDarkPurple
                                                )
                                            )
                                        }
                                    }

                                    // 2. Folder Selection Button & Browser Modal Trigger
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        color = BentoCardPurple,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BentoCardBorder.copy(alpha = 0.4f))
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    modifier = Modifier.weight(1f),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(CircleShape)
                                                            .background(BentoPrimaryContainer),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.FolderOpen,
                                                            contentDescription = null,
                                                            tint = BentoPrimary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    Column {
                                                        Text(
                                                            text = "Music Folders",
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = BentoTextPrimary
                                                        )
                                                        Text(
                                                            text = when {
                                                                localFullScan -> "All storage scanned (${includedFolders.size} inc, ${excludedFolders.size} exc)"
                                                                includedFolders.isNotEmpty() || excludedFolders.isNotEmpty() -> "${includedFolders.size} included, ${excludedFolders.size} excluded"
                                                                else -> "Default: standard Music folders"
                                                            },
                                                            fontSize = 11.5.sp,
                                                            color = BentoTextSecondary
                                                        )
                                                    }
                                                }

                                                FilledTonalButton(
                                                    onClick = { showFolderBrowserModal = true },
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                                    colors = ButtonDefaults.filledTonalButtonColors(
                                                        containerColor = BentoPrimaryContainer,
                                                        contentColor = BentoPrimary
                                                    )
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Folder,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Select Folders", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            // Folders Chips Preview
                                            if (folders.isNotEmpty()) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    folders.take(3).forEach { f ->
                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = if (f.isExcluded) Color(0xFFF44336).copy(alpha = 0.2f) else Color(0xFF4CAF50).copy(alpha = 0.2f)
                                                        ) {
                                                            Text(
                                                                text = if (f.isExcluded) "✕ ${f.path.substringAfterLast("/")}" else "✓ ${f.path.substringAfterLast("/")}",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = if (f.isExcluded) Color(0xFFF44336) else Color(0xFF4CAF50),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                    if (folders.size > 3) {
                                                        Text(
                                                            text = "+${folders.size - 3} more",
                                                            fontSize = 10.sp,
                                                            color = BentoTextSecondary,
                                                            modifier = Modifier.align(Alignment.CenterVertically)
                                                        )
                                                    }
                                                }
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = BentoCardDarkPurple.copy(alpha = 0.5f),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        text = "Tip: Tap 'Select Folders' to include or exclude specific directories.",
                                                        fontSize = 11.sp,
                                                        color = BentoTextSubtle,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 3. Audio Duration Filter (Material 3 RangeSlider: 0 - 300 seconds)
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        color = BentoCardPurple,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BentoCardBorder.copy(alpha = 0.4f))
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Timelapse,
                                                        contentDescription = null,
                                                        tint = BentoPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Text(
                                                        text = "Song Duration Filter",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoTextPrimary
                                                    )
                                                }

                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = BentoPrimaryContainer
                                                ) {
                                                    val minVal = localMinSec.roundToInt()
                                                    val maxVal = localMaxSec.roundToInt()
                                                    val maxLabel = if (maxVal >= 300) "300s+ (No Max)" else "${maxVal}s"
                                                    Text(
                                                        text = "${minVal}s – $maxLabel",
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoOnPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }

                                            RangeSlider(
                                                value = localMinSec..localMaxSec,
                                                onValueChange = { range ->
                                                    localMinSec = range.start
                                                    localMaxSec = range.endInclusive
                                                    onSetSongDurationRange(
                                                        range.start.roundToInt(),
                                                        range.endInclusive.roundToInt()
                                                    )
                                                },
                                                valueRange = 0f..300f,
                                                steps = 59, // 5s increments (0, 5, 10, ... 300)
                                                colors = SliderDefaults.colors(
                                                    thumbColor = BentoPrimary,
                                                    activeTrackColor = BentoPrimary,
                                                    inactiveTrackColor = BentoCardDarkPurple
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            val minS = localMinSec.roundToInt()
                                            val maxS = localMaxSec.roundToInt()
                                            Text(
                                                text = when {
                                                    minS == 0 && maxS >= 300 -> "Include all audio files (no minimum or maximum length cutoff)"
                                                    minS > 0 && maxS >= 300 -> "Filter files shorter than ${minS}s (skips ringtones & voice notes; no upper limit)"
                                                    minS == 0 && maxS < 300 -> "Filter files longer than ${maxS}s (excludes long podcasts or recordings)"
                                                    else -> "Only index audio tracks between ${minS}s and ${maxS}s"
                                                },
                                                fontSize = 11.sp,
                                                color = BentoTextSecondary
                                            )
                                        }
                                    }

                                    HorizontalDivider(color = BentoCardBorder.copy(alpha = 0.3f), thickness = 1.dp)

                                    // 4. Start Library Scan Button
                                    Button(
                                        onClick = { onStartScan(localFullScan) },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !scanProgress.isScanning,
                                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (scanProgress.isScanning) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = BentoOnPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (scanProgress.isScanning) "Scanning Audio Files..." else "Start Library Scan",
                                            color = BentoOnPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }

                                    // Scan Progress Display (Accurate, Genuine Counts)
                                    if (scanProgress.isScanning) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            LinearProgressIndicator(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(CircleShape),
                                                color = BentoPrimary
                                            )
                                            Text(
                                                text = if (scanProgress.currentFolder.isNotBlank()) {
                                                    "Scanning ${scanProgress.currentFolder} (${scanProgress.validTracksFound} tracks identified)..."
                                                } else {
                                                    "Identified ${scanProgress.validTracksFound} genuine audio tracks..."
                                                },
                                                fontSize = 12.sp,
                                                color = BentoTextSecondary,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else if (scanProgress.isFinished) {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFF1B5E20).copy(alpha = 0.3f),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f))
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = Color(0xFF4CAF50),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Text(
                                                        text = "Identified ${scanProgress.validTracksFound} genuine audio tracks",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF4CAF50)
                                                    )
                                                }
                                                Text(
                                                    text = "Matching your selected scope, folders & duration threshold",
                                                    fontSize = 11.sp,
                                                    color = BentoTextSecondary,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Navigation Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 1) {
                    OutlinedButton(
                        onClick = { currentStep-- },
                        shape = RoundedCornerShape(14.dp)
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
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (currentStep == 3) "Get Started" else "Next",
                        color = BentoOnPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Hierarchical Folder Browser Modal
        if (showFolderBrowserModal) {
            HierarchicalFolderBrowserModal(
                folders = folders,
                onAddFolder = onAddFolder,
                onRemoveFolder = onRemoveFolder,
                onDismiss = { showFolderBrowserModal = false }
            )
        }
    }
}
