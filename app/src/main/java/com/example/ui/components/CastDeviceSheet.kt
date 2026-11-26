package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 22.dp)
            ) {
                AnimatedContent(
                    targetState = showConnectionGuide,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(150)) },
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
                        CastDevicesListContent(
                            castState = castState,
                            currentSong = currentSong,
                            audioRouteInfo = audioRouteInfo,
                            availableAudioDevices = availableAudioDevices,
                            onStartScan = onStartScan,
                            onOpenHelpGuide = { showConnectionGuide = true },
                            onSelectCastDevice = onSelectCastDevice,
                            onDisconnectCast = onDisconnectCast,
                            onToggleRemotePlayPause = onToggleRemotePlayPause,
                            onSkipNext = onSkipNext,
                            onSkipPrevious = onSkipPrevious,
                            onSeekRemote = onSeekRemote,
                            onSetRemoteVolume = onSetRemoteVolume,
                            onQuickPlayOnCast = onQuickPlayOnCast,
                            onSelectAudioDevice = onSelectAudioDevice,
                            onLaunchSystemCast = {
                                onLaunchSystemCast()
                                onDismiss()
                            },
                            onOpenWifiSettings = onOpenWifiSettings,
                            onDismiss = onDismiss
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastDevicesListContent(
    castState: CastSessionState,
    currentSong: Song?,
    audioRouteInfo: AudioRouteInfo,
    availableAudioDevices: List<AudioRouteDevice>,
    onStartScan: () -> Unit,
    onOpenHelpGuide: () -> Unit,
    onSelectCastDevice: (CastDevice) -> Unit,
    onDisconnectCast: () -> Unit,
    onToggleRemotePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekRemote: (Long) -> Unit,
    onSetRemoteVolume: (Float) -> Unit,
    onQuickPlayOnCast: () -> Unit,
    onSelectAudioDevice: (AudioRouteDevice) -> Unit,
    onLaunchSystemCast: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (castState.isConnected) BentoPrimary else BentoPrimary.copy(alpha = 0.14f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (castState.isConnected) Icons.Default.CastConnected else Icons.Default.Cast,
                        contentDescription = null,
                        tint = if (castState.isConnected) BentoOnPrimary else BentoPrimary,
                        modifier = Modifier.size(21.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cast to TV & Devices",
                        fontSize = 18.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitleText = when {
                        castState.isConnected -> "Connected to ${castState.connectedDevice?.name}"
                        castState.isConnecting -> castState.statusMessage ?: "Connecting…"
                        castState.isScanning -> "Scanning Wi-Fi & nearby devices…"
                        else -> "Stream songs to TV, speaker or display"
                    }
                    Text(
                        text = subtitleText,
                        fontSize = 12.sp,
                        color = if (castState.isConnected) BentoPrimary else BentoTextSecondary,
                        fontWeight = if (castState.isConnected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onStartScan()
                    },
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
                            contentDescription = "Scan for nearby TVs and devices",
                            tint = BentoPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismiss()
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("btn_cast_close")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Cast sheet",
                        tint = BentoTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 2. Prominent "How should I connect?" Help Banner + Wi-Fi Readiness Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val wifiColor = if (castState.isWifiConnected) Color(0xFF22C55E) else Color(0xFFF59E0B)
            Surface(
                onClick = {
                    if (!castState.isWifiConnected) {
                        HapticUtils.performClickHaptic(context)
                        onOpenWifiSettings()
                    }
                },
                shape = RoundedCornerShape(14.dp),
                color = wifiColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, wifiColor.copy(alpha = 0.32f)),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 44.dp)
                    .testTag("cast_wifi_status_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        imageVector = if (castState.isWifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = wifiColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (castState.isWifiConnected) "Wi-Fi Ready" else "Turn on Wi-Fi",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onOpenHelpGuide()
                },
                shape = RoundedCornerShape(14.dp),
                color = BentoPrimary.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.38f)),
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .testTag("btn_how_to_connect_help")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "How should I connect help",
                        tint = BentoPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "How should I connect?",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoPrimary,
                        maxLines = 1
                    )
                }
            }
        }

        // Error Banner (if any)
        if (!castState.errorMessage.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFEF4444).copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = castState.errorMessage,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = BentoTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. Active Remote Cast Controller Card (when connected to a TV/Cast device)
        val connectedDevice = castState.connectedDevice
        if (castState.isConnected && connectedDevice != null) {
            ActiveCastControllerCard(
                device = connectedDevice,
                castState = castState,
                fallbackSong = currentSong,
                onTogglePlayPause = onToggleRemotePlayPause,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onSeekRemote = onSeekRemote,
                onSetRemoteVolume = onSetRemoteVolume,
                onQuickPlayOnCast = onQuickPlayOnCast,
                onDisconnectCast = onDisconnectCast
            )
        }

        // 4. Nearby TVs & Wi-Fi Cast Devices Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NEARBY TVs & WI-FI DEVICES",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = BentoTextSubtle,
                letterSpacing = 0.9.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
            if (castState.discoveredDevices.isNotEmpty()) {
                Text(
                    text = "${castState.discoveredDevices.size} found",
                    fontSize = 11.sp,
                    color = BentoPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BentoCardPurple.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (castState.discoveredDevices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (castState.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = BentoPrimary
                        )
                        Text(
                            text = "Looking for TVs & Cast devices…",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Searching for Chromecast, Google TV & DLNA Smart TVs on your Wi-Fi",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = BentoPrimary.copy(alpha = 0.78f),
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "No Smart TVs or Cast devices found",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = if (castState.isWifiConnected) {
                                "Make sure your TV is powered on and connected to the same Wi-Fi network as this phone."
                            } else {
                                "Connect your phone to Wi-Fi first so OffGrid Music can find nearby Smart TVs and Chromecast."
                            },
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = BentoTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onStartScan()
                                },
                                shape = CircleShape,
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 44.dp)
                                    .testTag("btn_cast_empty_scan")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Scan Again",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoPrimary
                                )
                            }

                            Button(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onOpenHelpGuide()
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BentoPrimary,
                                    contentColor = BentoOnPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 44.dp)
                                    .testTag("btn_cast_empty_help")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "How to Connect",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    castState.discoveredDevices.forEachIndexed { idx, device ->
                        val isSelected = castState.connectedDevice?.id == device.id && castState.isConnected
                        val isConnectingThis = castState.connectedDevice?.id == device.id && castState.isConnecting
                        val rowBg by animateColorAsState(
                            targetValue = if (isSelected) BentoPrimary.copy(alpha = 0.14f) else Color.Transparent,
                            animationSpec = tween(220),
                            label = "cast_device_bg"
                        )

                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                if (isSelected) onDisconnectCast() else onSelectCastDevice(device)
                            },
                            color = rowBg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 62.dp)
                                .testTag("cast_device_${device.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) BentoPrimary else BentoTextPrimary.copy(alpha = 0.08f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = when (device.kind) {
                                            CastDeviceKind.SPEAKER -> Icons.Default.Speaker
                                            CastDeviceKind.DISPLAY -> Icons.Default.CastConnected
                                            else -> Icons.Default.Tv
                                        },
                                        contentDescription = null,
                                        tint = if (isSelected) BentoOnPrimary else BentoTextPrimary.copy(alpha = 0.9f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSelected) BentoPrimary else BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = BentoPrimary.copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = device.protocol.label,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoPrimary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                        Text(
                                            text = if (isSelected) "Casting active" else device.subtitle,
                                            fontSize = 11.5.sp,
                                            color = if (isSelected) BentoPrimary.copy(alpha = 0.85f) else BentoTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                when {
                                    isConnectingThis -> CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = BentoPrimary
                                    )
                                    isSelected -> Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(BentoPrimary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Connected",
                                            tint = BentoOnPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    else -> Icon(
                                        imageVector = Icons.Default.Cast,
                                        contentDescription = "Connect to ${device.name}",
                                        tint = BentoTextSecondary.copy(alpha = 0.65f),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }

                        if (idx < castState.discoveredDevices.lastIndex) {
                            HorizontalDivider(
                                color = BentoBorder.copy(alpha = 0.3f),
                                thickness = 0.6.dp,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            )
                        }
                    }
                }
            }
        }

        // 5. Connected Bluetooth & Audio Outputs (Soundbars, Bluetooth TVs, Speakers)
        if (availableAudioDevices.isNotEmpty()) {
            Text(
                text = "BLUETOOTH & AUDIO OUTPUTS",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = BentoTextSubtle,
                letterSpacing = 0.9.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = BentoCardPurple.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.38f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    availableAudioDevices.forEachIndexed { idx, device ->
                        val isCurrent = !castState.isConnected && (
                            device.isSelected ||
                                (device.name.equals(audioRouteInfo.name, ignoreCase = true) && device.type == audioRouteInfo.type)
                            )
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                if (castState.isConnected) onDisconnectCast()
                                onSelectAudioDevice(device)
                            },
                            color = if (isCurrent) BentoPrimary.copy(alpha = 0.11f) else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 56.dp)
                                .testTag("cast_audio_route_${device.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isCurrent) BentoPrimary else BentoTextPrimary.copy(alpha = 0.08f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = device.icon,
                                        contentDescription = null,
                                        tint = if (isCurrent) BentoOnPrimary else BentoTextPrimary.copy(alpha = 0.85f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isCurrent) BentoPrimary else BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isCurrent) "Active output" else device.description,
                                        fontSize = 11.sp,
                                        color = if (isCurrent) BentoPrimary.copy(alpha = 0.82f) else BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Active output",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                        if (idx < availableAudioDevices.lastIndex) {
                            HorizontalDivider(
                                color = BentoBorder.copy(alpha = 0.25f),
                                thickness = 0.6.dp,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            )
                        }
                    }
                }
            }
        }

        // 6. System Cast / Wireless Display Fallback Button
        Button(
            onClick = {
                HapticUtils.performClickHaptic(context)
                onLaunchSystemCast()
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = BentoPrimary.copy(alpha = 0.14f),
                contentColor = BentoPrimary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("btn_system_cast_settings")
        ) {
            Icon(
                imageVector = Icons.Default.ScreenShare,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "System Cast / Wireless Display",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActiveCastControllerCard(
    device: CastDevice,
    castState: CastSessionState,
    fallbackSong: Song?,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeekRemote: (Long) -> Unit,
    onSetRemoteVolume: (Float) -> Unit,
    onQuickPlayOnCast: () -> Unit,
    onDisconnectCast: () -> Unit
) {
    val context = LocalContext.current
    val activeSong = castState.castingSong ?: fallbackSong
    val durationMs = castState.remoteDurationMs.takeIf { it > 0L } ?: activeSong?.durationMs ?: 0L
    var isDraggingSeek by remember { mutableStateOf(false) }
    var dragSeekFraction by remember { mutableFloatStateOf(0f) }
    val displayFraction = if (isDraggingSeek) {
        dragSeekFraction
    } else if (durationMs > 0L) {
        (castState.remotePositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = BentoPrimary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_cast_control_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CastConnected,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "CASTING TO ${device.name.uppercase(Locale.US)}",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoPrimary,
                        letterSpacing = 0.7.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDisconnectCast()
                    },
                    shape = RoundedCornerShape(50),
                    color = Color(0xFFEF4444).copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                    modifier = Modifier.testTag("btn_stop_casting")
                ) {
                    Text(
                        text = "Stop Casting",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            if (activeSong != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AlbumArtView(
                        song = activeSong,
                        modifier = Modifier.size(48.dp),
                        cornerRadius = 10
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeSong.displayTitle,
                            fontSize = 14.sp,
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

                // Remote Progress Slider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = displayFraction,
                        onValueChange = { frac ->
                            isDraggingSeek = true
                            dragSeekFraction = frac
                        },
                        onValueChangeFinished = {
                            isDraggingSeek = false
                            if (durationMs > 0L) {
                                onSeekRemote((dragSeekFraction * durationMs).toLong())
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = BentoPrimary,
                            activeTrackColor = BentoPrimary,
                            inactiveTrackColor = BentoTextSecondary.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val posMs = if (isDraggingSeek && durationMs > 0L) (dragSeekFraction * durationMs).toLong() else castState.remotePositionMs
                        Text(
                            text = formatCastTime(posMs),
                            fontSize = 10.5.sp,
                            color = BentoTextSecondary
                        )
                        Text(
                            text = formatCastTime(durationMs),
                            fontSize = 10.5.sp,
                            color = BentoTextSecondary
                        )
                    }
                }

                // Remote Transport Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onSkipPrevious()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("btn_cast_prev")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous track on TV",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onTogglePlayPause()
                        },
                        shape = CircleShape,
                        color = BentoPrimary,
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("btn_cast_play_pause")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (castState.isRemotePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (castState.isRemotePlaying) "Pause on TV" else "Play on TV",
                                tint = BentoOnPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onSkipNext()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("btn_cast_next")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next track on TV",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Connected! Pick any song to start playing on ${device.name}.",
                        fontSize = 12.sp,
                        color = BentoTextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onQuickPlayOnCast()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("btn_cast_quick_play")
                    ) {
                        Text("Play Song", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Remote TV Volume Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeDown,
                    contentDescription = "Decrease TV volume",
                    tint = BentoTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Slider(
                    value = castState.remoteVolume,
                    onValueChange = { onSetRemoteVolume(it) },
                    colors = SliderDefaults.colors(
                        thumbColor = BentoPrimary,
                        activeTrackColor = BentoPrimary,
                        inactiveTrackColor = BentoTextSecondary.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Increase TV volume",
                    tint = BentoTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun formatCastTime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
