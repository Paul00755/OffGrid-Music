package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cast.CastSessionState
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.HapticUtils

/**
 * Simple, easy-to-understand "How it works" connection guide shown inside the Cast sheet.
 */
@Composable
fun CastConnectionGuideContent(
    castState: CastSessionState,
    currentSong: Song?,
    onOpenWifiSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onBackToDevices: () -> Unit,
    onBackAndRescan: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    HapticUtils.performTickHaptic(context)
                    onBackToDevices()
                },
                modifier = Modifier
                    .size(44.dp)
                    .testTag("btn_cast_guide_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Cast devices",
                    tint = BentoTextPrimary
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "How should I connect?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
                Text(
                    text = "3 quick steps to play music on your TV",
                    fontSize = 12.sp,
                    color = BentoTextSecondary
                )
            }
        }

        // Live Quick Status Pill Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusCheckChip(
                label = if (castState.isWifiConnected) "Wi-Fi Connected" else "Wi-Fi Off",
                isReady = castState.isWifiConnected,
                icon = if (castState.isWifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                modifier = Modifier.weight(1f)
            )
            StatusCheckChip(
                label = if (currentSong != null) "Song Ready" else "Pick Any Song",
                isReady = currentSong != null,
                icon = Icons.Default.MusicNote,
                modifier = Modifier.weight(1f)
            )
        }

        // 3 Simple Steps
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = BentoCardPurple.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SimpleGuideStep(
                    number = "1",
                    title = "Join the same Wi-Fi",
                    description = "Connect your phone and TV to the exact same home Wi-Fi (avoid Guest Wi-Fi or VPN)."
                )
                SimpleGuideStep(
                    number = "2",
                    title = "Tap your TV in the list",
                    description = "Go back, tap your TV name, and your offline song + artwork + synced lyrics stream straight over local Wi-Fi."
                )
                SimpleGuideStep(
                    number = "3",
                    title = "Control from anywhere",
                    description = "Use the app, home widgets, phone volume buttons, or connected headphones — everything stays synced with your TV."
                )
            }
        }

        // Quick Device Tips
        Text(
            text = "SUPPORTED DEVICES & TIPS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            color = BentoTextSecondary,
            modifier = Modifier.padding(start = 2.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickDeviceTipRow(
                icon = Icons.Default.CastConnected,
                title = "Chromecast, Google TV & Android TV",
                detail = "Appears automatically with live synced lyrics support."
            )
            QuickDeviceTipRow(
                icon = Icons.Default.Tv,
                title = "Samsung, LG, Sony, Roku & Smart TVs",
                detail = "Turn on DLNA / Media Sharing in TV Network Settings, then tap Scan."
            )
            QuickDeviceTipRow(
                icon = Icons.Default.Bluetooth,
                title = "Bluetooth TVs, Soundbars & Speakers",
                detail = "No Wi-Fi needed — pair once in Bluetooth Settings and select it under Bluetooth & Audio."
            )
            QuickDeviceTipRow(
                icon = Icons.Default.LightMode,
                title = "TV Screensaver Tip",
                detail = "If your Android TV still dims during long audio sessions, increase its Screen Saver timeout in TV Settings → System → Ambient Mode."
            )
        }

        // Quick Action Footer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    HapticUtils.performTickHaptic(context)
                    onOpenWifiSettings()
                },
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
            ) {
                Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp), tint = BentoTextPrimary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Wi-Fi", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
            }
            OutlinedButton(
                onClick = {
                    HapticUtils.performTickHaptic(context)
                    onOpenBluetoothSettings()
                },
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp), tint = BentoTextPrimary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Bluetooth", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
            }
        }

        Button(
            onClick = {
                HapticUtils.performClickHaptic(context)
                onBackAndRescan()
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("btn_cast_guide_rescan")
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Back & Scan for TVs", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusCheckChip(
    label: String,
    isReady: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val tint = if (isReady) Color(0xFF34C759) else Color(0xFFFF9F0A)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.28f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SimpleGuideStep(
    number: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(BentoPrimary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = BentoPrimary
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoTextPrimary
            )
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 16.5.sp,
                color = BentoTextSecondary
            )
        }
    }
}

@Composable
private fun QuickDeviceTipRow(
    icon: ImageVector,
    title: String,
    detail: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BentoCardPurple.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BentoPrimary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextPrimary
                )
                Text(
                    text = detail,
                    fontSize = 11.5.sp,
                    lineHeight = 15.5.sp,
                    color = BentoTextSecondary
                )
            }
        }
    }
}
