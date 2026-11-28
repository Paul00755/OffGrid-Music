package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.AudioRouteDevice
import com.example.util.AudioRouteInfo
import com.example.util.HapticUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputSwitcherSheet(
    audioRouteInfo: AudioRouteInfo,
    availableDevices: List<AudioRouteDevice>,
    onSelectDevice: (AudioRouteDevice) -> Unit,
    onLaunchSystemSwitcher: () -> Unit,
    onDismiss: () -> Unit,
    isCastingToTv: Boolean = false,
    castDeviceName: String? = null
) {
    val context = LocalContext.current
    val effectiveCastingLocked = isCastingToTv ||
        audioRouteInfo.type == com.example.util.AudioDeviceType.TV_CAST

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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Clean Editorial Sheet Header
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
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BentoPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (effectiveCastingLocked) Icons.Default.Tv else Icons.Default.SpeakerGroup,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Audio Output",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (effectiveCastingLocked) {
                                    if (!castDeviceName.isNullOrBlank()) "Playing in TV · $castDeviceName" else "Playing in TV"
                                } else {
                                    "Playing on ${audioRouteInfo.name}"
                                },
                                fontSize = 12.sp,
                                color = BentoPrimary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDismiss()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close audio output sheet",
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (effectiveCastingLocked) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = BentoPrimary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.42f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("audio_output_locked_tv_banner")
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
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(BentoPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = "Playing in TV",
                                    tint = BentoOnPrimary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Playing in TV",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Audio output is locked while casting${if (!castDeviceName.isNullOrBlank()) " to $castDeviceName" else ""}. Disconnect Cast to switch phone speakers or Bluetooth.",
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Output locked while casting",
                                tint = BentoPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Clean Grouped Device Surface (non-boxy unified list with hairline dividers)
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = BentoCardPurple.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (effectiveCastingLocked) 0.52f else 1f)
                ) {
                    if (availableDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No external audio devices detected",
                                fontSize = 13.sp,
                                color = BentoTextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            itemsIndexed(availableDevices, key = { _, d -> d.id }) { idx, device ->
                                val isCurrent = !effectiveCastingLocked && (
                                    device.isSelected ||
                                        (device.name.equals(audioRouteInfo.name, ignoreCase = true) && device.type == audioRouteInfo.type)
                                    )

                                val rowBg by animateColorAsState(
                                    targetValue = if (isCurrent) BentoPrimary.copy(alpha = 0.13f) else Color.Transparent,
                                    animationSpec = tween(220),
                                    label = "audio_device_row_bg"
                                )

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Surface(
                                        onClick = {
                                            if (!effectiveCastingLocked) {
                                                HapticUtils.performClickHaptic(context)
                                                onSelectDevice(device)
                                            }
                                        },
                                        enabled = !effectiveCastingLocked,
                                        color = rowBg,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 60.dp)
                                            .testTag("audio_device_${device.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isCurrent) BentoPrimary else BentoTextPrimary.copy(alpha = 0.08f)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = device.icon,
                                                    contentDescription = null,
                                                    tint = if (isCurrent) BentoOnPrimary else BentoTextPrimary.copy(alpha = 0.88f),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = device.name,
                                                    fontSize = 14.5.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                                    color = if (isCurrent) BentoPrimary else BentoTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = when {
                                                        effectiveCastingLocked -> "Locked while playing in TV"
                                                        isCurrent -> "Active output"
                                                        else -> device.description
                                                    },
                                                    fontSize = 12.sp,
                                                    color = if (isCurrent) BentoPrimary.copy(alpha = 0.82f) else BentoTextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            if (effectiveCastingLocked) {
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = "Locked",
                                                    tint = BentoTextSecondary.copy(alpha = 0.65f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            } else if (isCurrent) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .background(BentoPrimary),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Active Device",
                                                        tint = BentoOnPrimary,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                }
                                            } else {
                                                RadioButton(
                                                    selected = false,
                                                    onClick = null,
                                                    colors = RadioButtonDefaults.colors(
                                                        unselectedColor = BentoTextSecondary.copy(alpha = 0.45f)
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    if (idx < availableDevices.lastIndex) {
                                        HorizontalDivider(
                                            color = BentoBorder.copy(alpha = 0.3f),
                                            thickness = 0.6.dp,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Sleek Pill System Output Switcher Action
                Button(
                    onClick = {
                        if (!effectiveCastingLocked) {
                            HapticUtils.performClickHaptic(context)
                            onLaunchSystemSwitcher()
                            onDismiss()
                        }
                    },
                    enabled = !effectiveCastingLocked,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimary.copy(alpha = 0.14f),
                        contentColor = BentoPrimary,
                        disabledContainerColor = BentoTextSecondary.copy(alpha = 0.10f),
                        disabledContentColor = BentoTextSecondary.copy(alpha = 0.55f)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("btn_system_audio_switcher")
                ) {
                    Icon(
                        imageVector = if (effectiveCastingLocked) Icons.Default.Lock else Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (effectiveCastingLocked) "Output Locked · Playing in TV" else "System Media Output & Bluetooth",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
