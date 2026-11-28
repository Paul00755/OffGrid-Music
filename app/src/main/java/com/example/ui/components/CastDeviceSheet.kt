package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cast.CastConnectionState
import com.example.cast.CastDevice
import com.example.cast.CastDeviceKind
import com.example.cast.CastProtocol
import com.example.cast.CastSessionState
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.AudioRouteDevice
import com.example.util.AudioRouteInfo
import com.example.util.HapticUtils
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDeviceSheet(
    castState: CastSessionState,
    currentSong: Song?,
    audioRouteInfo: AudioRouteInfo,
    availableAudioDevices: List<AudioRouteDevice>,
    initialShowHelp: Boolean = false,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectCastDevice: (CastDevice) -> Unit,
    onDisconnectCast: () -> Unit,
    onToggleRemotePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekRemote: (Long) -> Unit,
    onSetRemoteVolume: (Float) -> Unit,
    onToggleTvLyrics: (Boolean) -> Unit = {},
    onQuickPlayOnCast: () -> Unit,
    onSelectAudioDevice: (AudioRouteDevice) -> Unit,
    onLaunchSystemCast: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showConnectionGuide by remember(initialShowHelp) { mutableStateOf(initialShowHelp) }

    LaunchedEffect(Unit) {
        onStartScan()
    }
    DisposableEffect(Unit) {
        onDispose { onStopScan() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BentoCardSurface,
        contentColor = BentoTextPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(BentoTextSecondary.copy(alpha = 0.28f))
            )
        }
    ) {
        BackHandler(enabled = showConnectionGuide) {
            showConnectionGuide = false
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 540.dp)
                    .fillMaxWidth()
                    .heightIn(max = 660.dp)
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 20.dp)
            ) {
                AnimatedContent(
                    targetState = showConnectionGuide,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(140)) },
                    label = "cast_sheet_mode"
                ) { inGuideMode ->
                    if (inGuideMode) {
                        CastConnectionGuideContent(
                            castState = castState,
                            currentSong = currentSong,
                            onOpenWifiSettings = onOpenWifiSettings,
                            onOpenBluetoothSettings = onOpenBluetoothSettings,
                            onBackToDevices = { showConnectionGuide = false },
                            onBackAndRescan = {
                                showConnectionGuide = false
                                onStartScan()
                            }
                        )
                    } else {
                        CastDevicesMainContent(
                            castState = castState,
                            currentSong = currentSong,
                            audioRouteInfo = audioRouteInfo,
                            availableAudioDevices = availableAudioDevices,
                            onOpenHelpGuide = {
                                HapticUtils.performTickHaptic(context)
                                showConnectionGuide = true
                            },
                            onRescan = {
                                HapticUtils.performTickHaptic(context)
                                onStartScan()
                            },
                            onSelectCastDevice = { dev ->
                                HapticUtils.performClickHaptic(context)
                                onSelectCastDevice(dev)
                            },
                            onDisconnectCast = {
                                HapticUtils.performClickHaptic(context)
                                onDisconnectCast()
                            },
                            onToggleRemotePlayPause = {
                                HapticUtils.performTickHaptic(context)
                                onToggleRemotePlayPause()
                            },
                            onSkipNext = {
                                HapticUtils.performTickHaptic(context)
                                onSkipNext()
                            },
                            onSkipPrevious = {
                                HapticUtils.performTickHaptic(context)
                                onSkipPrevious()
                            },
                            onSeekRemote = onSeekRemote,
                            onSetRemoteVolume = onSetRemoteVolume,
                            onToggleTvLyrics = { enabled ->
                                HapticUtils.performTickHaptic(context)
                                onToggleTvLyrics(enabled)
                            },
                            onQuickPlayOnCast = {
                                HapticUtils.performTickHaptic(context)
                                onQuickPlayOnCast()
                            },
                            onSelectAudioDevice = { dev ->
                                HapticUtils.performTickHaptic(context)
                                onSelectAudioDevice(dev)
                            },
                            onLaunchSystemCast = {
                                HapticUtils.performTickHaptic(context)
                                onLaunchSystemCast()
                            },
                            onOpenWifiSettings = onOpenWifiSettings
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastDevicesMainContent(
    castState: CastSessionState,
    currentSong: Song?,
    audioRouteInfo: AudioRouteInfo,
    availableAudioDevices: List<AudioRouteDevice>,
    onOpenHelpGuide: () -> Unit,
    onRescan: () -> Unit,
    onSelectCastDevice: (CastDevice) -> Unit,
    onDisconnectCast: () -> Unit,
    onToggleRemotePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekRemote: (Long) -> Unit,
    onSetRemoteVolume: (Float) -> Unit,
    onToggleTvLyrics: (Boolean) -> Unit,
    onQuickPlayOnCast: () -> Unit,
    onSelectAudioDevice: (AudioRouteDevice) -> Unit,
    onLaunchSystemCast: () -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Clean Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(BentoPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (castState.isConnected) Icons.Default.CastConnected else Icons.Default.Cast,
                    contentDescription = "Cast",
                    tint = BentoPrimary,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cast to TV & Devices",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = when {
                    castState.isConnected -> "Playing on ${castState.connectedDevice?.name ?: "TV"}"
                    castState.isConnecting -> castState.statusMessage ?: "Connecting…"
                    castState.isWifiConnected -> "Wi-Fi ready"
                    else -> "Connect to Wi-Fi to find TVs"
                }
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = if (castState.isConnected) Color(0xFF34C759) else BentoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Compact "How it works" help button
            Surface(
                onClick = onOpenHelpGuide,
                shape = RoundedCornerShape(12.dp),
                color = BentoPrimary.copy(alpha = 0.12f),
                modifier = Modifier
                    .heightIn(min = 38.dp)
                    .testTag("btn_how_to_connect_help")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "How it works",
                        tint = BentoPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "How it works",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onRescan,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("btn_cast_rescan")
            ) {
                if (castState.isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = BentoPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan for devices",
                        tint = BentoTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 2. Wi-Fi Warning Banner (only shown if Wi-Fi is off)
        if (!castState.isWifiConnected) {
            Surface(
                onClick = onOpenWifiSettings,
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFF9F0A).copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color(0xFFFF9F0A).copy(alpha = 0.30f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = Color(0xFFFF9F0A),
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wi-Fi is turned off",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Tap to join the same Wi-Fi network as your TV",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Wi-Fi Settings",
                        tint = BentoTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 3. Error Banner (if any)
        castState.errorMessage?.let { err ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFF453A).copy(alpha = 0.11f),
                border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFFF453A),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = err,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = BentoTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4. Active Remote Control Card (when connected to a TV)
        if (castState.isConnected && castState.connectedDevice != null) {
            ActiveCastRemoteCard(
                castState = castState,
                activeSong = castState.castingSong ?: currentSong,
                onDisconnect = onDisconnectCast,
                onTogglePlayPause = onToggleRemotePlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onSeekRemote = onSeekRemote,
                onSetRemoteVolume = onSetRemoteVolume,
                onToggleTvLyrics = onToggleTvLyrics,
                onQuickPlay = onQuickPlayOnCast
            )
        }

        // 5. Nearby TVs & Speakers List
        Text(
            text = "NEARBY TVS & SPEAKERS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            color = BentoTextSecondary,
            modifier = Modifier.padding(top = 2.dp, start = 2.dp)
        )

        if (castState.discoveredDevices.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = BentoCardPurple.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = BentoTextSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        text = if (castState.isScanning) "Looking for TVs on your Wi-Fi…" else "No TVs found yet",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Make sure your TV is powered on and connected to the same Wi-Fi network.",
                        fontSize = 12.sp,
                        color = BentoTextSecondary,
                        lineHeight = 16.sp
                    )
                    if (!castState.isScanning) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onRescan,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                                modifier = Modifier.heightIn(min = 42.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan Again", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = onOpenHelpGuide,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.heightIn(min = 42.dp)
                            ) {
                                Text("How it works", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                            }
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                castState.discoveredDevices.forEach { device ->
                    val isSelected = castState.connectedDevice?.id == device.id &&
                        (castState.isConnected || castState.isConnecting)
                    val isConnectingThis = castState.connectedDevice?.id == device.id &&
                        castState.connectionState == CastConnectionState.CONNECTING

                    CastDeviceRow(
                        device = device,
                        isSelected = isSelected,
                        isConnecting = isConnectingThis,
                        onClick = {
                            if (isSelected && castState.isConnected) {
                                onDisconnectCast()
                            } else {
                                onSelectCastDevice(device)
                            }
                        }
                    )
                }
            }
        }

        // 6. Optional Paired Bluetooth Audio & System Cast Shortcut
        val externalAudioDevices = remember(availableAudioDevices) {
            availableAudioDevices.filter { it.type != com.example.util.AudioDeviceType.SPEAKER }
        }
        if (externalAudioDevices.isNotEmpty()) {
            Text(
                text = "BLUETOOTH & AUDIO",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
                color = BentoTextSecondary,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                externalAudioDevices.forEach { dev ->
                    val isCastingLocked = castState.isActiveSession
                    val isActive = dev.isSelected && !isCastingLocked
                    Surface(
                        onClick = { if (!isCastingLocked) onSelectAudioDevice(dev) },
                        enabled = !isCastingLocked,
                        shape = RoundedCornerShape(14.dp),
                        color = if (isActive) BentoPrimary.copy(alpha = 0.10f) else BentoCardPurple.copy(alpha = if (isCastingLocked) 0.28f else 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (isActive) BentoPrimary.copy(alpha = 0.35f) else BentoBorder.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = dev.icon,
                                contentDescription = dev.name,
                                tint = if (isActive) BentoPrimary else BentoTextSecondary.copy(alpha = if (isCastingLocked) 0.55f else 1f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dev.name,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isCastingLocked) BentoTextPrimary.copy(alpha = 0.6f) else BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isCastingLocked) "Output locked · Playing in TV" else if (isActive) "Active audio output" else "Tap to switch audio",
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary
                                )
                            }
                            if (isActive) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Active",
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // System Wireless Display shortcut
        Surface(
            onClick = onLaunchSystemCast,
            shape = RoundedCornerShape(14.dp),
            color = BentoCardPurple.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.22f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_system_cast_settings")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ScreenShare,
                    contentDescription = "System Cast",
                    tint = BentoTextSecondary,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "System Cast / Screen Mirroring",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = if (castState.isActiveSession) "Current output: Playing in TV" else "Current phone output: ${audioRouteInfo.name}",
                        fontSize = 11.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = BentoTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ActiveCastRemoteCard(
    castState: CastSessionState,
    activeSong: Song?,
    onDisconnect: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekRemote: (Long) -> Unit,
    onSetRemoteVolume: (Float) -> Unit,
    onToggleTvLyrics: (Boolean) -> Unit,
    onQuickPlay: () -> Unit
) {
    val device = castState.connectedDevice ?: return
    val durationMs = castState.remoteDurationMs.takeIf { it > 0L } ?: activeSong?.durationMs ?: 0L
    var isDraggingSeek by remember { mutableStateOf(false) }
    var dragSeekMs by remember { mutableLongStateOf(0L) }
    val displayPosMs = if (isDraggingSeek) dragSeekMs else castState.remotePositionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = BentoPrimary.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.30f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_cast_remote_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top bar: Connected TV name + Disconnect button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34C759))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = device.name,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onDisconnect,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .testTag("btn_stop_casting")
                ) {
                    Text(
                        text = "Disconnect",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFF453A)
                    )
                }
            }

            if (activeSong != null) {
                // Song row + TV Lyrics toggle chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtView(
                        song = activeSong,
                        modifier = Modifier.size(48.dp),
                        cornerRadius = 11
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeSong.displayTitle,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = activeSong.displayArtist,
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Compact seek bar
                if (durationMs > 0L) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Slider(
                            value = (displayPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                            onValueChange = { frac ->
                                isDraggingSeek = true
                                dragSeekMs = (frac * durationMs).toLong()
                            },
                            onValueChangeFinished = {
                                isDraggingSeek = false
                                onSeekRemote(dragSeekMs)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = BentoPrimary,
                                activeTrackColor = BentoPrimary,
                                inactiveTrackColor = BentoTextSecondary.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatCastDuration(displayPosMs), fontSize = 10.5.sp, color = BentoTextSecondary)
                            Text(formatCastDuration(durationMs), fontSize = 10.5.sp, color = BentoTextSecondary)
                        }
                    }
                }

                // Transport Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onSkipPrevious,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(18.dp))
                    FilledIconButton(
                        onClick = onTogglePlayPause,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = BentoPrimary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("btn_cast_play_pause")
                    ) {
                        Icon(
                            imageVector = if (castState.isRemotePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (castState.isRemotePlaying) "Pause" else "Play",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(18.dp))
                    IconButton(
                        onClick = onSkipNext,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            } else {
                Button(
                    onClick = onQuickPlay,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Play Music on TV", fontWeight = FontWeight.SemiBold)
                }
            }

            // TV Volume Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeDown,
                        contentDescription = "Lower TV volume",
                        tint = BentoTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Slider(
                        value = castState.remoteVolume.coerceIn(0f, 1f),
                        onValueChange = onSetRemoteVolume,
                        colors = SliderDefaults.colors(
                            thumbColor = BentoPrimary,
                            activeTrackColor = BentoPrimary,
                            inactiveTrackColor = BentoTextSecondary.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Raise TV volume",
                        tint = BentoTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "${(castState.remoteVolume * 100).toInt()}%",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextSecondary,
                        modifier = Modifier.width(34.dp)
                    )
                }
                Text(
                    text = "Phone volume buttons, widgets & headphones also control your TV",
                    fontSize = 11.sp,
                    color = BentoTextSecondary.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun CastDeviceRow(
    device: CastDevice,
    isSelected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    val icon = when (device.kind) {
        CastDeviceKind.TV -> Icons.Default.Tv
        CastDeviceKind.SPEAKER -> Icons.Default.Speaker
        CastDeviceKind.DISPLAY -> Icons.Default.SmartDisplay
        CastDeviceKind.GENERIC -> Icons.Default.Cast
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) BentoPrimary.copy(alpha = 0.12f) else BentoCardPurple.copy(alpha = 0.55f),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) BentoPrimary.copy(alpha = 0.45f) else BentoBorder.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cast_device_${device.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) BentoPrimary.copy(alpha = 0.18f) else BentoCardSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = device.name,
                    tint = if (isSelected) BentoPrimary else BentoTextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.protocol.label,
                    fontSize = 11.5.sp,
                    color = BentoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            when {
                isConnecting -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = BentoPrimary
                )
                isSelected -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Connected",
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(21.dp)
                )
                else -> Text(
                    text = "Connect",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoPrimary
                )
            }
        }
    }
}

private fun formatCastDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
