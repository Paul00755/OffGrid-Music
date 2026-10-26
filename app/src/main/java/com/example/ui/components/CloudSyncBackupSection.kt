package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.sync.BackupFrequency
import com.example.data.sync.BackupStatus
import com.example.data.sync.SyncStatus
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated "Cloud Synchronization and Backup" section used in Settings → User Account
 * and the Account Screen.
 * 
 * Includes:
 * - Automatic Backup enable/disable switch
 * - Backup frequency selection (Daily, Weekly, Monthly)
 * - Real-time "Last Synced" & "Last Backup" timestamps
 * - "Sync Now" & "Backup Now" immediate triggers
 * - Fully conforms to the existing dynamic theme system.
 */
@Composable
fun CloudSyncBackupSection(
    automaticBackupEnabled: Boolean,
    onToggleAutomaticBackup: (Boolean) -> Unit,
    backupFrequency: BackupFrequency,
    onSelectFrequency: (BackupFrequency) -> Unit,
    lastSyncedTimestamp: Long,
    lastBackupTimestamp: Long,
    syncStatus: SyncStatus,
    backupStatus: BackupStatus,
    onSyncNow: () -> Unit,
    onBackupNow: () -> Unit,
    isUserSignedIn: Boolean,
    onOpenAuth: () -> Unit,
    onSignInGoogle: () -> Unit = onOpenAuth,
    modifier: Modifier = Modifier
) {
    var pendingAction by remember { mutableStateOf<PendingCloudAction?>(null) }
    val dateYearFormatter = remember {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = BentoCardSurface,
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = BentoPrimary.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cloud Synchronization & Backup",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Manual cloud sync and scheduled backups",
                        fontSize = 12.sp,
                        color = BentoTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = BentoBorder.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(18.dp))

            if (!isUserSignedIn) {
                Box {
                    Column(
                        modifier = Modifier.fillMaxWidth().blur(7.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Automatic Backup", color = BentoTextPrimary, fontWeight = FontWeight.SemiBold)
                            Switch(checked = automaticBackupEnabled, onCheckedChange = {}, enabled = false)
                        }
                        Text("FREQUENCY     Daily       Weekly       Monthly", color = BentoTextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Sync Now") }
                            Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Backup Now") }
                        }
                        Text("Last Synced        Last Backup", color = BentoTextSecondary)
                    }
                    Box(
                        modifier = Modifier.matchParentSize().clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {}
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = BentoCardSurface.copy(alpha = 0.97f),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.5f)),
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = BentoPrimary)
                            Text("Sign in to use Cloud & Sync", fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                            Text(
                                "Your backup and sync settings stay protected until you connect a Google or email account.",
                                color = BentoTextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = onSignInGoogle, modifier = Modifier.fillMaxWidth()) {
                                Text("Sign in with Google")
                            }
                            OutlinedButton(onClick = onOpenAuth, modifier = Modifier.fillMaxWidth()) {
                                Text("Sign in with Email / Password")
                            }
                        }
                    }
                }
            } else {
                // Signed In: Full Cloud Synchronization & Backup Controls

                // 1. Automatic Backup Toggle Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Automatic Backup",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (automaticBackupEnabled) BentoPrimary.copy(alpha = 0.16f) else BentoTextSubtle.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, if (automaticBackupEnabled) BentoPrimary.copy(alpha = 0.4f) else BentoBorder.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = if (automaticBackupEnabled) "ON" else "OFF",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (automaticBackupEnabled) BentoPrimary else BentoTextSubtle,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Automatically back up your data using background WorkManager",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            lineHeight = 16.sp
                        )
                    }

                    Switch(
                        checked = automaticBackupEnabled,
                        onCheckedChange = onToggleAutomaticBackup,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BentoOnPrimary,
                            checkedTrackColor = BentoPrimary,
                            uncheckedThumbColor = BentoTextSubtle,
                            uncheckedTrackColor = BentoCardSurface
                        )
                    )
                }

                // 2. Backup Frequency Selector (Animated expansion when enabled)
                AnimatedVisibility(
                    visible = automaticBackupEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        Text(
                            text = "FREQUENCY",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSubtle,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BackupFrequency.values().forEach { freq ->
                                val isSelected = freq == backupFrequency
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) BentoPrimary else BentoCardPurple,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSelectFrequency(freq) }
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = freq.displayName,
                                            fontSize = 12.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) BentoOnPrimary else BentoTextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = BentoBorder.copy(alpha = 0.35f))
                Spacer(modifier = Modifier.height(16.dp))

                // 3. Last Synced & Last Backup Bento Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Last Synced Tile
                    TimestampTile(
                        title = "Last Synced",
                        timestamp = lastSyncedTimestamp,
                        dateFormatter = dateYearFormatter,
                        icon = Icons.Default.Sync,
                        statusText = when (syncStatus) {
                            is SyncStatus.Syncing -> "Syncing..."
                            is SyncStatus.Synced -> "Synced"
                            is SyncStatus.Error -> "Sync Error"
                            else -> "Ready"
                        },
                        statusColor = when (syncStatus) {
                            is SyncStatus.Syncing -> Color(0xFF00E5FF)
                            is SyncStatus.Synced -> Color(0xFF00E676)
                            is SyncStatus.Error -> Color(0xFFFFB74D)
                            else -> BentoTextSubtle
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // Last Backup Tile
                    TimestampTile(
                        title = "Last Backup",
                        timestamp = lastBackupTimestamp,
                        dateFormatter = dateYearFormatter,
                        icon = Icons.Default.CloudUpload,
                        statusText = when (backupStatus) {
                            is BackupStatus.BackingUp -> "Backing up..."
                            is BackupStatus.Success -> "Up to date"
                            is BackupStatus.Error -> "Backup Error"
                            else -> if (lastBackupTimestamp > 0) "Available" else "No backup yet"
                        },
                        statusColor = when (backupStatus) {
                            is BackupStatus.BackingUp -> BentoPrimary
                            is BackupStatus.Success -> Color(0xFF00E676)
                            is BackupStatus.Error -> Color(0xFFFF5252)
                            else -> BentoTextSubtle
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Action Buttons: [ Sync Now ] and [ Backup Now ]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Sync Now Button
                    val isSyncing = syncStatus is SyncStatus.Syncing
                    OutlinedButton(
                        onClick = { pendingAction = PendingCloudAction.Sync },
                        enabled = !isSyncing,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF00E5FF)
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF00E5FF)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Syncing...", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Now", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Backup Now Button
                    val isBackingUp = backupStatus is BackupStatus.BackingUp
                    Button(
                        onClick = { pendingAction = PendingCloudAction.Backup },
                        enabled = !isBackingUp,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary,
                            disabledContainerColor = BentoPrimary.copy(alpha = 0.5f),
                            disabledContentColor = BentoOnPrimary.copy(alpha = 0.7f)
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = BentoOnPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backing up...", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup Now", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                val operationFeedback = when {
                    syncStatus is SyncStatus.Error -> syncStatus.message
                    backupStatus is BackupStatus.Error -> backupStatus.message
                    syncStatus is SyncStatus.Synced -> "Cloud state synced successfully."
                    backupStatus is BackupStatus.Success -> "Cloud snapshot saved successfully."
                    else -> null
                }
                operationFeedback?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        fontSize = 11.5.sp,
                        color = if (syncStatus is SyncStatus.Error || backupStatus is BackupStatus.Error) {
                            Color(0xFFFF5252)
                        } else {
                            Color(0xFF00C853)
                        },
                        lineHeight = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Storage Notice: Retaining only latest snapshot
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = BentoTextSubtle,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Snapshot storage: Retains only the latest backup at users/{uid}/backup/latest.",
                        fontSize = 11.sp,
                        color = BentoTextSubtle
                    )
                }
            }
        }
    }

    pendingAction?.let { action ->
        val isBackup = action == PendingCloudAction.Backup
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(if (isBackup) "Back up current state?" else "Sync from cloud?") },
            text = {
                Text(
                    if (isBackup) {
                        "Upload this device's supported playlists, favourites, history, and settings as the authoritative cloud snapshot. Items deleted locally will be absent from the new snapshot. This does not download cloud data."
                    } else {
                        "Fetch the authoritative cloud state and apply it on this device. Local changes that have not been backed up may be replaced or reconciled. This does not upload local changes."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    if (isBackup) onBackupNow() else onSyncNow()
                }) {
                    Text(if (isBackup) "Upload Backup" else "Apply Cloud Sync")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("Cancel") }
            }
        )
    }
}

private enum class PendingCloudAction { Sync, Backup }

@Composable
private fun TimestampTile(
    title: String,
    timestamp: Long,
    dateFormatter: SimpleDateFormat,
    icon: ImageVector,
    statusText: String,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BentoCardPurple,
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextSubtle
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (timestamp > 0) {
                    dateFormatter.format(Date(timestamp))
                } else {
                    "Never"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = BentoTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = statusText,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }
        }
    }
}
