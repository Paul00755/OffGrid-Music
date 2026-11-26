package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private data class CastMethodGuide(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val icon: ImageVector,
    val steps: List<String>
)

/**
 * Interactive "How should I connect?" step-by-step help guide shown inside the Cast sheet.
 * Includes live phone readiness checks, method-specific instructions, troubleshooting tips,
 * and one-tap shortcuts to Wi-Fi / Bluetooth settings.
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
    var expandedMethodId by remember { mutableStateOf("google_cast") }

    val guides = remember {
        listOf(
            CastMethodGuide(
                id = "google_cast",
                title = "Chromecast, Google TV & Android TV",
                subtitle = "Google Cast • Nest Hub & Speakers",
                badge = "Best",
                icon = Icons.Default.CastConnected,
                steps = listOf(
                    "Power on your TV, Chromecast, or Nest speaker and make sure it is connected to the exact same Wi-Fi network as this phone.",
                    "Go back to the Devices list and tap Scan. Your TV will appear with a Google Cast badge.",
                    "Tap your TV name to connect. OffGrid Music will stream your offline song and album artwork directly over your local Wi-Fi.",
                    "Use Play, Pause, Next, Seek, and the TV Volume slider right from your phone."
                )
            ),
            CastMethodGuide(
                id = "dlna_tv",
                title = "Samsung, LG, Sony, Roku & Smart TVs",
                subtitle = "DLNA / UPnP Smart TVs & Wi-Fi Speakers",
                badge = "Wi-Fi",
                icon = Icons.Default.Tv,
                steps = listOf(
                    "Connect both your phone and Smart TV to the same home Wi-Fi (avoid Guest Wi-Fi).",
                    "On your TV, make sure Media Sharing / DLNA / External Device Access is turned ON in Network Settings. On Roku, open the Roku Media Player app.",
                    "Return to the Devices list and tap Scan. Wait 2–3 seconds for your TV (labeled DLNA Smart TV) to show up.",
                    "Tap your TV name. If your TV displays an Allow / Accept connection popup, confirm it with your TV remote."
                )
            ),
            CastMethodGuide(
                id = "bluetooth_audio",
                title = "Bluetooth TVs, Soundbars & Speakers",
                subtitle = "Works even without Wi-Fi",
                badge = "No Wi-Fi",
                icon = Icons.Default.Bluetooth,
                steps = listOf(
                    "Put your TV, soundbar, or wireless speaker into Bluetooth Pairing mode (on TVs: Settings → Sound → Sound Output → Bluetooth).",
                    "Tap Bluetooth Settings below and pair your device with your phone once.",
                    "Return to OffGrid Music and select the paired device under Bluetooth & Audio Outputs inside the Cast sheet."
                )
            ),
            CastMethodGuide(
                id = "wireless_display",
                title = "Wireless Display / Miracast / Smart View",
                subtitle = "System screen & audio casting",
                badge = "System",
                icon = Icons.Default.ScreenShare,
                steps = listOf(
                    "Enable Screen Mirroring, Wireless Display, or Smart View on your TV input menu.",
                    "Go back to the Cast sheet and tap System Cast / Wireless Display at the bottom.",
                    "Choose your TV from Android's built-in Cast screen to mirror audio & playback."
                )
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Guide Header with Back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onBackToDevices()
                },
                shape = CircleShape,
                color = BentoCardDarkPurple.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                modifier = Modifier
                    .size(40.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("btn_cast_guide_back")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Cast devices",
                        tint = BentoTextPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "How should I connect?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Follow these steps to cast songs to your TV or speaker",
                    fontSize = 11.5.sp,
                    color = BentoTextSecondary,
                    maxLines = 2
                )
            }
        }

        // Live Pre-Flight Readiness Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = BentoCardPurple.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "QUICK CONNECTION CHECKLIST",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = BentoTextSubtle,
                    letterSpacing = 0.9.sp
                )

                ReadinessCheckRow(
                    isReady = castState.isWifiConnected,
                    title = if (castState.isWifiConnected) "Wi-Fi is connected" else "Wi-Fi is not connected",
                    subtitle = if (castState.isWifiConnected) {
                        val ipPart = castState.localIpAddress?.let { " ($it)" }.orEmpty()
                        "Make sure your TV is on this same Wi-Fi$ipPart"
                    } else {
                        "Turn on Wi-Fi to discover Smart TVs & Chromecast"
                    }
                )

                ReadinessCheckRow(
                    isReady = currentSong != null,
                    title = if (currentSong != null) "Song ready to cast" else "No song selected yet",
                    subtitle = if (currentSong != null) {
                        "${currentSong.displayTitle} • ${currentSong.displayArtist}"
                    } else {
                        "You can connect first, then tap any song in your library"
                    }
                )
            }
        }

        // Expandable Method Cards
        Text(
            text = "CHOOSE YOUR TV OR DEVICE TYPE",
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            color = BentoTextSubtle,
            letterSpacing = 0.9.sp,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )

        guides.forEach { guide ->
            val isExpanded = expandedMethodId == guide.id
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isExpanded) BentoPrimary.copy(alpha = 0.11f) else BentoCardDarkPurple.copy(alpha = 0.62f),
                border = BorderStroke(
                    1.dp,
                    if (isExpanded) BentoPrimary.copy(alpha = 0.48f) else BentoBorder.copy(alpha = 0.42f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cast_guide_method_${guide.id}")
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                HapticUtils.performClickHaptic(context)
                                expandedMethodId = if (isExpanded) "" else guide.id
                            }
                            .defaultMinSize(minHeight = 56.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BentoPrimary.copy(alpha = if (isExpanded) 0.22f else 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = guide.icon,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = guide.title,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = BentoPrimary.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = guide.badge,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = guide.subtitle,
                                fontSize = 11.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse steps" else "Expand steps",
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HorizontalDivider(color = BentoBorder.copy(alpha = 0.3f))
                            guide.steps.forEachIndexed { idx, step ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 1.dp)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(BentoPrimary.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary
                                        )
                                    }
                                    Text(
                                        text = step,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        color = BentoTextPrimary.copy(alpha = 0.92f),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Troubleshooting Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BentoCardDarkPurple.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.TipsAndUpdates,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "TV not showing up? Quick fixes",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                }
                TroubleshootBullet("Pause any active VPN app — VPNs block local TV discovery.")
                TroubleshootBullet("Avoid Guest Wi-Fi or router Client/AP Isolation, which hides devices from each other.")
                TroubleshootBullet("Keep your phone connected to Wi-Fi while casting — offline songs stream directly from your phone to the TV.")
            }
        }

        // Quick Settings Shortcuts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onOpenWifiSettings()
                },
                shape = CircleShape,
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_cast_guide_wifi")
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = BentoPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Wi-Fi Settings",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onOpenBluetoothSettings()
                },
                shape = CircleShape,
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_cast_guide_bluetooth")
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = BentoPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Bluetooth",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Button(
            onClick = {
                HapticUtils.performClickHaptic(context)
                onBackAndRescan()
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = BentoPrimary,
                contentColor = BentoOnPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("btn_cast_guide_rescan")
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Back to Devices & Scan Now",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ReadinessCheckRow(
    isReady: Boolean,
    title: String,
    subtitle: String
) {
    val badgeColor = if (isReady) Color(0xFF22C55E) else Color(0xFFF59E0B)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(badgeColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                contentDescription = null,
                tint = badgeColor,
                modifier = Modifier.size(16.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = BentoTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TroubleshootBullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = BentoPrimary
        )
        Text(
            text = text,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = BentoTextSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}
