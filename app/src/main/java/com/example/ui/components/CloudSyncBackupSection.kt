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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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
 * Dedicated "Cloud Synchronization & Backup" section used in Settings -> Cloud & Sync
 * and the Account Screen. Harmonized with BentoPrimary and clean Material 3 pill actions.
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
    showOuterCard: Boolean = true,
    modifier: Modifier = Modifier
) {
    var pendingAction by remember { mutableStateOf<PendingCloudAction?>(null) }
    val dateYearFormatter = remember {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    }

    val content: @Composable ColumnScope.() -> Unit = {
        if (!isUserSignedIn) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .blur(7.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Automatic Backup", color = BentoTextPrimary, fontWeight = FontWeight.SemiBold)
                        Switch(checked = automaticBackupEnabled, onCheckedChange = {}, enabled = false)
                    }
                    Text("FREQUENCY   Daily   Weekly   Monthly", fontSize = 12.sp, color = BentoTextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {}, enabled = false, shape = CircleShape, modifier = Modifier.weight(1f)) {
                            Text("Sync Now")
                        }
                        Button(onClick = {}, enabled = false, shape = CircleShape, modifier = Modifier.weight(1f)) {
                            Text("Backup Now")
                        }
                    }
                    Text("Last Synced  •  Last Backup", fontSize = 12.sp, color = BentoTextSecondary)
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {}
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = BentoCardSurface.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Sign in to use Cloud & Sync",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Connect a Google or email account to back up and restore your playlists, favorites, stats, and settings.",
                            color = BentoTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onSignInGoogle,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BentoPrimary,
                                contentColor = BentoOnPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 46.dp)
                        ) {
                            Text("Sign in with Google", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onOpenAuth,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, BentoBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoTextPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 46.dp)
                        ) {
                            Text("Sign in with Email / Password", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        } else {
            // 1. Automatic Backup Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
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
                            color = if (automaticBackupEnabled) BentoPrimary.copy(alpha = 0.16f) else BentoTextSubtle.copy(alpha = 0.14f)
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
                        text = "Automatically back up your library data in the background",
                        fontSize = 11.5.sp,
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

            // 2. Backup Frequency Selector
            AnimatedVisibility(
                visible = automaticBackupEnabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(
                        text = "SCHEDULE FREQUENCY",
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
                                shape = CircleShape,
                                color = if (isSelected) BentoPrimary else BentoCardPurple.copy(alpha = 0.65f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .clickable { onSelectFrequency(freq) }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Text(
                                        text = freq.displayName,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) BentoOnPrimary else BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = BentoBorder.copy(alpha = 0.30f))
            Spacer(modifier = Modifier.height(14.dp))

            // 3. Last Synced & Last Backup Status Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                        is SyncStatus.Syncing -> BentoPrimary
                        is SyncStatus.Synced -> Color(0xFF00C853)
                        is SyncStatus.Error -> Color(0xFFFFB74D)
                        else -> BentoTextSubtle
                    },
                    modifier = Modifier.weight(1f)
                )

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
                        is BackupStatus.Success -> Color(0xFF00C853)
                        is BackupStatus.Error -> Color(0xFFFF5252)
                        else -> BentoTextSubtle
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Pill Action Buttons: [ Sync Now ] and [ Backup Now ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isSyncing = syncStatus is SyncStatus.Syncing
                OutlinedButton(
                    onClick = { pendingAction = PendingCloudAction.Sync },
                    enabled = !isSyncing,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = BentoPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Syncing...", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Now", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }

                val isBackingUp = backupStatus is BackupStatus.BackingUp
                Button(
                    onClick = { pendingAction = PendingCloudAction.Backup },
                    enabled = !isBackingUp,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimary,
                        contentColor = BentoOnPrimary,
                        disabledContainerColor = BentoPrimary.copy(alpha = 0.5f),
                        disabledContentColor = BentoOnPrimary.copy(alpha = 0.7f)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    if (isBackingUp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = BentoOnPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Backing up...", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Backup Now", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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

            Spacer(modifier = Modifier.height(12.dp))

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
                    text = "Retains the latest encrypted cloud snapshot for your account.",
                    fontSize = 11.sp,
                    color = BentoTextSubtle
                )
            }
        }
    }

    if (showOuterCard) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
            modifier = modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BentoPrimary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cloud Synchronization & Backup",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Manual cloud sync and scheduled backups",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = BentoBorder.copy(alpha = 0.30f))
                Spacer(modifier = Modifier.height(14.dp))
                content()
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            content()
        }
    }

    pendingAction?.let { action ->
        val isBackup = action == PendingCloudAction.Backup
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = if (isBackup) "Back up current state?" else "Sync from cloud?",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                Text(
                    text = if (isBackup) {
                        "Upload this device's supported playlists, favorites, history, and settings as your latest cloud snapshot. Items deleted locally will be absent from the new snapshot."
                    } else {
                        "Fetch your latest cloud snapshot and apply it on this device. Local changes that have not been backed up may be replaced or reconciled."
                    },
                    fontSize = 13.sp,
                    color = BentoTextSecondary,
                    lineHeight = 18.5.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingAction = null
                        if (isBackup) onBackupNow() else onSyncNow()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimary,
                        contentColor = BentoOnPrimary
                    )
                ) {
                    Text(
                        text = if (isBackup) "Upload Backup" else "Apply Cloud Sync",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
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
        color = BentoCardPurple.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
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
                fontSize = 12.5.sp,
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
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
