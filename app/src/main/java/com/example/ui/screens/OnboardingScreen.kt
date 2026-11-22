package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.example.R
import com.example.data.model.FolderInfo
import com.example.scanner.ScanProgress
import com.example.ui.components.BentoCard
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlin.math.roundToInt

enum class InitialScanMode {
    SPECIFIC_FOLDERS,
    FULL_DEVICE
}

private data class OnboardingThemeOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val previewBg: Color,
    val previewCard: Color,
    val previewAccent: Color,
    val previewInk: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun OnboardingScreen(
    scanProgress: ScanProgress,
    hasAllFilesAccess: Boolean = false,
    folders: List<FolderInfo> = emptyList(),
    ignoreDurationSec: Int = 0,
    maxDurationSec: Int = 300,
    fullDeviceScan: Boolean = false,
    selectedThemeMode: String = "DARK",
    isSystemDark: Boolean = true,
    nowPlayingLayout: String = "MINIMAL_GLASS",
    homeScreenLayout: String = "HERO_SPOTLIGHT",
    playlistsLayout: String = "BENTO_GRID",
    lyricsLayout: String = "CLASSIC_DEFAULT",
    onSelectThemeMode: (String) -> Unit = {},
    onConfirmLayouts: (nowPlaying: String, home: String, playlists: String, lyrics: String) -> Unit = { _, _, _, _ -> },
    onSetFullDeviceScan: (Boolean) -> Unit = {},
    onSetIgnoreAudioDuration: (Int) -> Unit = {},
    onSetSongDurationRange: (minSec: Int, maxSec: Int) -> Unit = { _, _ -> },
    onAddFolder: (path: String, name: String, isExcluded: Boolean) -> Unit = { _, _, _ -> },
    onRemoveFolder: (path: String) -> Unit = {},
    onRequestStoragePermission: () -> Unit = {},
    onStartScan: (forceFullScan: Boolean) -> Unit = {},
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val totalSteps = 4
    var currentStep by remember { mutableIntStateOf(1) }
    var localFullScan by remember(fullDeviceScan) { mutableStateOf(fullDeviceScan) }
    var isPickingForExclusion by remember { mutableStateOf(false) }
    var showOnboardingLayoutStudio by remember { mutableStateOf(false) }
    var hasCustomizedLayouts by remember { mutableStateOf(false) }

    // Confirmation dialogs when Full-Device Scan is toggled or started while folders are configured
    var showFullScanToggleConfirmDialog by remember { mutableStateOf(false) }
    var showFullScanStartConfirmDialog by remember { mutableStateOf(false) }

    val includedFolders = remember(folders) { folders.filter { !it.isExcluded } }
    val excludedFolders = remember(folders) { folders.filter { it.isExcluded } }

    // Full-screen structural shadow UI Layout Studio during Onboarding Step 2
    if (showOnboardingLayoutStudio) {
        UiLayoutStudioScreen(
            initialTabId = "NOW_PLAYING",
            currentNowPlayingLayout = nowPlayingLayout,
            currentHomeScreenLayout = homeScreenLayout,
            currentPlaylistsLayout = playlistsLayout,
            currentLyricsLayout = lyricsLayout,
            waveformStyle = "SNAKE",
            themeMode = selectedThemeMode,
            isGradientEffect = true,
            isGlassEffect = true,
            useDarkColors = false,
            showVolumeBar = false,
            showAdvancedSongInfo = true,
            homeSections = listOf("CONTINUE_LISTENING", "QUICK_ACTIONS", "STATS", "RECENTLY_PLAYED", "MOST_PLAYED"),
            isOnboardingShadowMode = true,
            onConfirmLayouts = { np, home, pl, lyr ->
                hasCustomizedLayouts = true
                onConfirmLayouts(np, home, pl, lyr)
            },
            onBack = { showOnboardingLayoutStudio = false },
            modifier = modifier
        )
        return
    }

    // Handle system Back button inside Onboarding steps (Step 4 -> Step 3 -> Step 2 -> Step 1)
    BackHandler(enabled = currentStep > 1) {
        currentStep--
    }

    // Native Android System File Manager Folder Picker (OpenDocumentTree) — matches Settings UI
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val parts = docId.split(":")
                val storageType = parts.firstOrNull() ?: ""
                val subPath = if (parts.size > 1) parts[1] else ""

                val resolvedPath = when {
                    storageType.equals("primary", ignoreCase = true) -> {
                        if (subPath.isNotBlank()) "/storage/emulated/0/$subPath" else "/storage/emulated/0"
                    }
                    storageType.startsWith("raw:") -> {
                        storageType.removePrefix("raw:").trimEnd('/')
                    }
                    storageType.startsWith("/") -> {
                        if (subPath.isNotBlank()) "$storageType/$subPath" else storageType
                    }
                    else -> {
                        if (subPath.isNotBlank()) "/storage/$storageType/$subPath" else "/storage/$storageType"
                    }
                }

                val folderName = if (subPath.isNotBlank()) {
                    subPath.substringAfterLast('/')
                } else {
                    resolvedPath.substringAfterLast('/').ifBlank { "Selected Folder" }
                }

                onAddFolder(resolvedPath, folderName, isPickingForExclusion)
            } catch (_: Exception) {}
        }
    }

    // Duration filter state matching Settings UI (presets + smooth 0..180s slider)
    val durationPresets = remember {
        listOf(
            0 to "All Audio",
            15 to "15s",
            30 to "30s",
            45 to "45s",
            60 to "1m",
            120 to "2m"
        )
    }
    var currentSliderValue by remember(ignoreDurationSec) {
        mutableFloatStateOf(ignoreDurationSec.toFloat().coerceIn(0f, 180f))
    }
    var isDraggingSlider by remember { mutableStateOf(false) }
    val displayDuration = (if (isDraggingSlider) currentSliderValue.roundToInt() else ignoreDurationSec).coerceIn(0, 180)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BentoCardPurple, BentoBackground)
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Progress Dots
            Row(
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 1..totalSteps) {
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
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.5.dp, BentoPrimary.copy(alpha = 0.45f)),
                                    shadowElevation = 6.dp,
                                    modifier = Modifier.size(86.dp)
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.app_logo),
                                        contentDescription = "OffGrid Music Logo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Text(
                                    text = "Welcome to OffGrid Music",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
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
                        val normalizedMode = selectedThemeMode.uppercase()
                        val themeOptions = remember(isSystemDark) {
                            listOf(
                                OnboardingThemeOption(
                                    id = "DARK",
                                    title = "Dark",
                                    subtitle = "Midnight Obsidian lite-dark",
                                    previewBg = Color(0xFF0E111A),
                                    previewCard = Color(0xFF1A1F30),
                                    previewAccent = Color(0xFFA78BFA),
                                    previewInk = Color.White,
                                    icon = Icons.Default.DarkMode
                                ),
                                OnboardingThemeOption(
                                    id = "LIGHT",
                                    title = "Light",
                                    subtitle = "Crisp bright daylight UI",
                                    previewBg = Color(0xFFF6F7FB),
                                    previewCard = Color(0xFFFFFFFF),
                                    previewAccent = Color(0xFF6D28D9),
                                    previewInk = Color(0xFF0F172A),
                                    icon = Icons.Default.LightMode
                                ),
                                OnboardingThemeOption(
                                    id = "AMOLED",
                                    title = "AMOLED",
                                    subtitle = "Extra-dark pitch black",
                                    previewBg = Color(0xFF000000),
                                    previewCard = Color(0xFF0A0A0F),
                                    previewAccent = Color(0xFF38BDF8),
                                    previewInk = Color.White,
                                    icon = Icons.Default.Contrast
                                ),
                                OnboardingThemeOption(
                                    id = "SYSTEM",
                                    title = "System",
                                    subtitle = if (isSystemDark) "Follows OS (Dark)" else "Follows OS (Light)",
                                    previewBg = if (isSystemDark) Color(0xFF141824) else Color(0xFFEDF2FA),
                                    previewCard = if (isSystemDark) Color(0xFF202638) else Color(0xFFFFFFFF),
                                    previewAccent = Color(0xFF8B5CF6),
                                    previewInk = if (isSystemDark) Color.White else Color(0xFF0F172A),
                                    icon = Icons.Default.BrightnessAuto
                                )
                            )
                        }

                        val activeLayoutSummary = remember(nowPlayingLayout, homeScreenLayout, playlistsLayout, lyricsLayout) {
                            "${NowPlayingLayout.fromId(nowPlayingLayout).displayName} • ${HomeScreenLayout.fromId(homeScreenLayout).displayName}"
                        }

                        BentoCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 560.dp)
                                .verticalScroll(rememberScrollState()),
                            backgroundColor = BentoCardDarkPurple,
                            padding = 22.dp
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(BentoPrimaryContainer.copy(alpha = 0.85f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Palette,
                                        contentDescription = null,
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }

                                Text(
                                    text = "Choose Your Look",
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = "Pick the theme that fits your vibe. Your default Now Playing screen automatically adapts to match.",
                                    fontSize = 13.sp,
                                    color = BentoTextSecondary,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )

                                // 2x2 Visual Theme Cards
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    themeOptions.chunked(2).forEach { rowOptions ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            rowOptions.forEach { option ->
                                                val isSelected = normalizedMode == option.id
                                                Surface(
                                                    onClick = {
                                                        HapticUtils.performClickHaptic(context)
                                                        onSelectThemeMode(option.id)
                                                    },
                                                    shape = RoundedCornerShape(16.dp),
                                                    color = if (isSelected) BentoPrimary.copy(alpha = 0.14f) else BentoCardPurple,
                                                    border = BorderStroke(
                                                        width = if (isSelected) 2.dp else 1.dp,
                                                        color = if (isSelected) BentoPrimary else BentoCardBorder.copy(alpha = 0.65f)
                                                    ),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .testTag("onboarding_theme_${option.id.lowercase()}")
                                                ) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(12.dp),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        // Mini visual surface swatch
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(44.dp)
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(option.previewBg)
                                                                .border(
                                                                    width = 1.dp,
                                                                    color = option.previewInk.copy(alpha = 0.12f),
                                                                    shape = RoundedCornerShape(10.dp)
                                                                )
                                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.fillMaxSize(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .size(26.dp)
                                                                        .clip(CircleShape)
                                                                        .background(option.previewCard),
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    Icon(
                                                                        imageVector = option.icon,
                                                                        contentDescription = null,
                                                                        tint = option.previewAccent,
                                                                        modifier = Modifier.size(16.dp)
                                                                    )
                                                                }
                                                                Column(
                                                                    modifier = Modifier
                                                                        .weight(1f)
                                                                        .padding(start = 8.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                                ) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .fillMaxWidth(0.75f)
                                                                            .height(5.dp)
                                                                            .clip(CircleShape)
                                                                            .background(option.previewInk.copy(alpha = 0.72f))
                                                                    )
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .fillMaxWidth(0.50f)
                                                                            .height(4.dp)
                                                                            .clip(CircleShape)
                                                                            .background(option.previewAccent.copy(alpha = 0.75f))
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = option.title,
                                                                    fontSize = 14.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = if (isSelected) BentoPrimary else BentoTextPrimary,
                                                                    maxLines = 1
                                                                )
                                                                Text(
                                                                    text = option.subtitle,
                                                                    fontSize = 10.5.sp,
                                                                    color = BentoTextSecondary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                            if (isSelected) {
                                                                Icon(
                                                                    imageVector = Icons.Default.CheckCircle,
                                                                    contentDescription = "Selected",
                                                                    tint = BentoPrimary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Screen Layout & Feel Studio Entry inside Onboarding
                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        showOnboardingLayoutStudio = true
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    color = BentoPrimary.copy(alpha = 0.11f),
                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.34f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_customize_layouts_button")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(BentoPrimary.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesomeMosaic,
                                                contentDescription = null,
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (hasCustomizedLayouts) "Screen Layouts Customized" else "Choose Screen UI & Feel",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoTextPrimary
                                            )
                                            Text(
                                                text = activeLayoutSummary,
                                                fontSize = 11.sp,
                                                color = BentoTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = "Open Layout Studio",
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // Skip for now option -> advances smoothly to next onboarding step
                                TextButton(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        currentStep++
                                    },
                                    modifier = Modifier.testTag("onboarding_skip_ui_feel_button")
                                ) {
                                    Text(
                                        text = "Skip for now — I'll do it later in Settings",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = BentoTextSecondary
                                    )
                                }
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
                                    color = BentoTextPrimary,
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
                    4 -> {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            BentoCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = BentoCardDarkPurple,
                                padding = 18.dp
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    // Header
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
                                                color = BentoTextPrimary
                                            )
                                            Text(
                                                text = "Configure scan options, folders & short audio filter before scanning",
                                                fontSize = 12.sp,
                                                color = BentoTextSecondary
                                            )
                                        }
                                    }

                                    // 1. Full-Device Scan Toggle
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        color = BentoCardPurple,
                                        border = BorderStroke(
                                            1.dp,
                                            if (localFullScan) Color(0xFF2979FF).copy(alpha = 0.5f) else BentoCardBorder.copy(alpha = 0.4f)
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
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(Color(0xFF2979FF).copy(alpha = 0.15f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Devices,
                                                        contentDescription = null,
                                                        tint = Color(0xFF2979FF),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                                Column {
                                                    Text(
                                                        text = "Full-Device Scan",
                                                        fontSize = 14.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoTextPrimary
                                                    )
                                                    Text(
                                                        text = if (localFullScan) {
                                                            "Search the entire device file system for audio tracks"
                                                        } else {
                                                            "Restricted strictly to configured music directories"
                                                        },
                                                        fontSize = 11.5.sp,
                                                        color = BentoTextSecondary
                                                    )
                                                }
                                            }

                                            Switch(
                                                checked = localFullScan,
                                                onCheckedChange = { isChecked ->
                                                    if (isChecked && folders.isNotEmpty()) {
                                                        showFullScanToggleConfirmDialog = true
                                                    } else {
                                                        localFullScan = isChecked
                                                        onSetFullDeviceScan(isChecked)
                                                    }
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color(0xFF2979FF),
                                                    checkedTrackColor = Color(0xFF2979FF).copy(alpha = 0.35f),
                                                    uncheckedThumbColor = BentoTextSecondary,
                                                    uncheckedTrackColor = BentoCardDarkPurple
                                                )
                                            )
                                        }
                                    }

                                    // 2. Filter Short Audio Clips (Identical to Settings UI: Presets + 0..180s Slider)
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        color = BentoCardPurple,
                                        border = BorderStroke(1.dp, BentoCardBorder.copy(alpha = 0.4f))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(Color(0xFF2979FF).copy(alpha = 0.15f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.FilterList,
                                                            contentDescription = null,
                                                            tint = Color(0xFF2979FF),
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    Column {
                                                        Text(
                                                            text = "Filter Short Audio Clips",
                                                            fontSize = 14.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = BentoTextPrimary
                                                        )
                                                        Text(
                                                            text = "Ignore audio files shorter than the selected duration during library scan",
                                                            fontSize = 11.5.sp,
                                                            color = BentoTextSecondary
                                                        )
                                                    }
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (displayDuration > 0) Color(0xFF2979FF).copy(alpha = 0.2f) else BentoCardDarkPurple)
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = when {
                                                            displayDuration == 0 -> "All Files"
                                                            displayDuration < 60 -> "< ${displayDuration}s"
                                                            displayDuration % 60 == 0 -> "< ${displayDuration / 60}m"
                                                            else -> "< ${displayDuration / 60}m ${displayDuration % 60}s"
                                                        },
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (displayDuration > 0) Color(0xFF2979FF) else BentoTextSecondary
                                                    )
                                                }
                                            }

                                            // Quick Preset Chips (0s, 15s, 30s, 45s, 1m, 2m)
                                            LazyRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                items(durationPresets, key = { it.first }) { (sec, label) ->
                                                    val isSelected = displayDuration == sec
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = if (isSelected) Color(0xFF2979FF) else BentoCardDarkPurple,
                                                        border = BorderStroke(1.dp, if (isSelected) Color(0xFF2979FF) else BentoBorder.copy(alpha = 0.5f)),
                                                        modifier = Modifier.clickable {
                                                            HapticUtils.performClickHaptic(context)
                                                            isDraggingSlider = false
                                                            currentSliderValue = sec.toFloat()
                                                            onSetIgnoreAudioDuration(sec)
                                                        }
                                                    ) {
                                                        Text(
                                                            text = label,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (isSelected) Color.White else BentoTextSecondary,
                                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // Continuous Smooth Slider (0s .. 180s) matching Settings
                                            Slider(
                                                value = currentSliderValue,
                                                onValueChange = { newVal ->
                                                    isDraggingSlider = true
                                                    currentSliderValue = newVal
                                                },
                                                onValueChangeFinished = {
                                                    isDraggingSlider = false
                                                    val finalSec = currentSliderValue.roundToInt().coerceIn(0, 180)
                                                    onSetIgnoreAudioDuration(finalSec)
                                                },
                                                valueRange = 0f..180f,
                                                colors = SliderDefaults.colors(
                                                    thumbColor = Color(0xFF2979FF),
                                                    activeTrackColor = Color(0xFF2979FF),
                                                    inactiveTrackColor = BentoCardBorder
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }

                                    // 3. Custom Music Directories (Uses System File Manager via OpenDocumentTree, identical to Settings UI)
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        color = BentoCardPurple,
                                        border = BorderStroke(1.dp, BentoCardBorder.copy(alpha = 0.4f))
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
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Custom Music Directories",
                                                        fontSize = 14.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BentoTextPrimary
                                                    )
                                                    Text(
                                                        text = "Select explicit directories to include (+) or exclude (⊘) using your system file manager",
                                                        fontSize = 11.5.sp,
                                                        color = BentoTextSecondary
                                                    )
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    IconButton(
                                                        onClick = {
                                                            isPickingForExclusion = false
                                                            try {
                                                                folderPickerLauncher.launch(null)
                                                            } catch (_: Exception) {
                                                                Toast.makeText(context, "Unable to open system folder picker", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        modifier = Modifier
                                                            .clip(CircleShape)
                                                            .background(BentoCardDarkPurple)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Add,
                                                            contentDescription = "Include Folder",
                                                            tint = Color(0xFF2979FF)
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            isPickingForExclusion = true
                                                            try {
                                                                folderPickerLauncher.launch(null)
                                                            } catch (_: Exception) {
                                                                Toast.makeText(context, "Unable to open system folder picker", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        modifier = Modifier
                                                            .clip(CircleShape)
                                                            .background(BentoCardDarkPurple)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Block,
                                                            contentDescription = "Exclude Folder",
                                                            tint = Color.Red
                                                        )
                                                    }
                                                }
                                            }

                                            if (folders.isNotEmpty()) {
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    folders.forEach { folder ->
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(BentoCardDarkPurple.copy(alpha = 0.7f))
                                                                .border(BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)), RoundedCornerShape(10.dp))
                                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                Icon(
                                                                    imageVector = if (folder.isExcluded) Icons.Default.Block else Icons.Default.Folder,
                                                                    contentDescription = null,
                                                                    tint = if (folder.isExcluded) Color.Red else Color(0xFF2979FF),
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                                Spacer(modifier = Modifier.width(10.dp))
                                                                Column {
                                                                    Row(
                                                                        verticalAlignment = Alignment.CenterVertically,
                                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = folder.name,
                                                                            fontSize = 13.sp,
                                                                            fontWeight = FontWeight.SemiBold,
                                                                            color = BentoTextPrimary
                                                                        )
                                                                        Surface(
                                                                            shape = RoundedCornerShape(4.dp),
                                                                            color = if (folder.isExcluded) Color.Red.copy(alpha = 0.2f) else Color(0xFF2979FF).copy(alpha = 0.2f)
                                                                        ) {
                                                                            Text(
                                                                                text = if (folder.isExcluded) "EXCLUDED" else "INCLUDED",
                                                                                fontSize = 9.sp,
                                                                                fontWeight = FontWeight.Bold,
                                                                                color = if (folder.isExcluded) Color.Red else Color(0xFF2979FF),
                                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                                            )
                                                                        }
                                                                    }
                                                                    Text(
                                                                        text = folder.path,
                                                                        fontSize = 11.sp,
                                                                        color = BentoTextSubtle,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                            }
                                                            IconButton(
                                                                onClick = { onRemoveFolder(folder.path) },
                                                                modifier = Modifier.size(28.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Close,
                                                                    contentDescription = "Remove",
                                                                    tint = BentoTextSecondary,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = BentoCardDarkPurple.copy(alpha = 0.5f),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        text = "Tap '+' to include specific folders or '⊘' to exclude folders via your device file manager.",
                                                        fontSize = 11.sp,
                                                        color = BentoTextSubtle,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = BentoCardBorder.copy(alpha = 0.3f), thickness = 1.dp)

                                    // 4. Start Library Scan Button (Only scans when clicked!)
                                    Button(
                                        onClick = {
                                            if (localFullScan && folders.isNotEmpty()) {
                                                showFullScanStartConfirmDialog = true
                                            } else {
                                                onStartScan(localFullScan)
                                            }
                                        },
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
                                                    "Scanning ${scanProgress.currentFolder} (${scanProgress.validTracksFound} tracks found)..."
                                                } else {
                                                    "${scanProgress.validTracksFound} tracks found..."
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
                                            border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f))
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
                                                        text = "${scanProgress.validTracksFound} tracks found",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF4CAF50)
                                                    )
                                                }
                                                Text(
                                                    text = "Matching your selected folders & duration filter",
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
                        if (currentStep < totalSteps) {
                            currentStep++
                        } else {
                            onComplete()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (currentStep == totalSteps) "Get Started" else "Next",
                        color = BentoOnPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Confirmation Dialog when toggling Full-Device Scan ON while folders are configured
        if (showFullScanToggleConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showFullScanToggleConfirmDialog = false },
                title = {
                    Text(
                        text = "Enable Full-Device Scan?",
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                },
                text = {
                    Text(
                        text = "You have ${includedFolders.size} included and ${excludedFolders.size} excluded folder(s) configured.\n\nEnabling Full-Device Scan will scan and fetch audio tracks from all folders across your device, including folders you added to the exclude list.\n\nDo you want to proceed with Full-Device Scan?",
                        fontSize = 13.5.sp,
                        color = BentoTextSecondary,
                        lineHeight = 19.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showFullScanToggleConfirmDialog = false
                            localFullScan = true
                            onSetFullDeviceScan(true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF), contentColor = Color.White)
                    ) {
                        Text("Confirm Full Scan", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFullScanToggleConfirmDialog = false }) {
                        Text("Keep Folder Filters", color = BentoTextSecondary)
                    }
                },
                containerColor = BentoCardSurface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // Confirmation Dialog when clicking Start Scan with Full-Device Scan active and folders configured
        if (showFullScanStartConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showFullScanStartConfirmDialog = false },
                title = {
                    Text(
                        text = "Confirm Full-Device Scan",
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                },
                text = {
                    Text(
                        text = "Full-Device Scan is enabled while you have custom included/excluded folders (${includedFolders.size} included, ${excludedFolders.size} excluded).\n\nFull-Device Scan may also include folders that you added in the exclude list and fetch tracks from all folders on your device. Would you like to scan all folders or scan only your selected folders?",
                        fontSize = 13.5.sp,
                        color = BentoTextSecondary,
                        lineHeight = 19.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showFullScanStartConfirmDialog = false
                            onStartScan(true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF), contentColor = Color.White)
                    ) {
                        Text("Scan All Folders", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showFullScanStartConfirmDialog = false
                            localFullScan = false
                            onSetFullDeviceScan(false)
                            onStartScan(false)
                        }
                    ) {
                        Text("Use Selected Folders Only", color = BentoPrimary)
                    }
                },
                containerColor = BentoCardSurface,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}