package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.auth.UserAccount
import com.example.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    userAccount: UserAccount?,
    customDisplayName: String?,
    customAvatarUri: String?,
    effectiveDisplayName: String,
    syncStatus: com.example.data.sync.SyncStatus = com.example.data.sync.SyncStatus.Idle,
    backupStatus: com.example.data.sync.BackupStatus = com.example.data.sync.BackupStatus.Idle,
    lastSyncedTimestamp: Long = 0L,
    lastBackupTimestamp: Long = 0L,
    automaticBackupEnabled: Boolean = false,
    backupFrequency: com.example.data.sync.BackupFrequency = com.example.data.sync.BackupFrequency.WEEKLY,
    onToggleAutomaticBackup: (Boolean) -> Unit = {},
    onSelectFrequency: (com.example.data.sync.BackupFrequency) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onBackupNow: () -> Unit = {},
    onOpenAuth: () -> Unit = {},
    onUpdateDisplayName: (String) -> Unit,
    onUpdateAvatar: (String?) -> Unit,
    onSignOut: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var showEditNameDialog by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf(effectiveDisplayName) }
    var showSignOutConfirmDialog by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val targetFile = File(context.filesDir, "user_avatar_${userAccount?.uid ?: "local"}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                onUpdateAvatar(targetFile.toURI().toString())
                Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                onUpdateAvatar(uri.toString())
                Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var isNavigatingBack by remember { mutableStateOf(false) }
    val handleBack: () -> Unit = {
        if (!isNavigatingBack) {
            isNavigatingBack = true
            onBackClick()
        }
    }

    BackHandler(enabled = !isNavigatingBack) {
        handleBack()
    }

    val displayPhoto = customAvatarUri ?: userAccount?.photoUrl

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BentoBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Account",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = BentoTextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = BentoTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Profile Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Avatar with edit badge
                    Box(
                        contentAlignment = Alignment.BottomEnd,
                        modifier = Modifier.size(100.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.18f),
                            border = BorderStroke(2.dp, BentoPrimary.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (!displayPhoto.isNullOrBlank()) {
                                AsyncImage(
                                    model = displayPhoto,
                                    contentDescription = "Profile Photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            } else if (userAccount != null) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (effectiveDisplayName.firstOrNull() ?: 'U').uppercase(),
                                        fontSize = 38.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = "Profile Photo",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(60.dp)
                                    )
                                }
                            }
                        }

                        // Camera edit button
                        Surface(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            shape = CircleShape,
                            color = BentoPrimary,
                            border = BorderStroke(2.dp, BentoCardSurface),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Change photo",
                                    tint = BentoOnPrimary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Display Name Row with edit icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = effectiveDisplayName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = {
                                editedName = effectiveDisplayName
                                showEditNameDialog = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit name",
                                tint = BentoPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Email
                    Text(
                        text = userAccount?.email ?: "Offline Guest Account",
                        fontSize = 13.5.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Provider & Status Chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BentoPrimary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (userAccount?.providerId?.contains("google", true) == true)
                                        Icons.Default.AccountCircle
                                    else
                                        Icons.Default.Email,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = userAccount?.friendlyProviderName ?: "Local Offline",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BentoPrimary
                                )
                            }
                        }

                        if (customAvatarUri != null) {
                            TextButton(
                                onClick = { onUpdateAvatar(null) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Reset photo",
                                    fontSize = 11.5.sp,
                                    color = BentoTextSubtle
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Dedicated Cloud Synchronization & Backup Section
            com.example.ui.components.CloudSyncBackupSection(
                automaticBackupEnabled = automaticBackupEnabled,
                onToggleAutomaticBackup = onToggleAutomaticBackup,
                backupFrequency = backupFrequency,
                onSelectFrequency = onSelectFrequency,
                lastSyncedTimestamp = lastSyncedTimestamp,
                lastBackupTimestamp = lastBackupTimestamp,
                syncStatus = syncStatus,
                backupStatus = backupStatus,
                onSyncNow = onSyncNow,
                onBackupNow = onBackupNow,
                isUserSignedIn = userAccount != null,
                onOpenAuth = onOpenAuth
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Account Details Card
            if (userAccount != null) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "ACCOUNT DETAILS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSubtle,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // User ID Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(userAccount.uid))
                                    Toast.makeText(context, "User ID copied", Toast.LENGTH_SHORT).show()
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "User Identifier (UID)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = userAccount.uid,
                                    fontSize = 11.sp,
                                    color = BentoTextSubtle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy UID",
                                tint = BentoTextSubtle,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Sign Out Button
            OutlinedButton(
                onClick = { showSignOutConfirmDialog = true },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFF5252)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Sign Out",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Edit Name Dialog
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = {
                Text(
                    text = "Edit Display Name",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BentoPrimary,
                        unfocusedBorderColor = BentoBorder,
                        focusedContainerColor = BentoCardSurface,
                        unfocusedContainerColor = BentoCardSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editedName.isNotBlank()) {
                            onUpdateDisplayName(editedName)
                            showEditNameDialog = false
                            Toast.makeText(context, "Name updated", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("Save", color = BentoOnPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            }
        )
    }

    // Sign Out Confirmation Dialog
    if (showSignOutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirmDialog = false },
            title = {
                Text(
                    text = "Sign Out?",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to sign out?\n\nAll your downloaded tracks, offline playlists, favorites, and settings will remain completely safe on your device.",
                    fontSize = 13.5.sp,
                    color = BentoTextSecondary,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutConfirmDialog = false
                        onSignOut()
                        Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Sign Out", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirmDialog = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            }
        )
    }
}
