package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
    automaticBackupEnabled: Boolean = true,
    backupFrequency: com.example.data.sync.BackupFrequency = com.example.data.sync.BackupFrequency.WEEKLY,
    onToggleAutomaticBackup: (Boolean) -> Unit = {},
    onSelectFrequency: (com.example.data.sync.BackupFrequency) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onBackupNow: () -> Unit = {},
    onOpenAuth: () -> Unit = {},
    onSignInGoogle: () -> Unit = onOpenAuth,
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
    var showSignInConfirmDialog by remember { mutableStateOf(false) }

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
            } catch (_: Exception) {
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

    val displayPhoto = if (userAccount != null) (customAvatarUri ?: userAccount.photoUrl) else null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBackground)
    ) {
        GlassGradientBackdrop()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = BentoScreenBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    TopAppBar(
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        title = {
                            Column {
                                Text(
                                    text = "Account",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Profile, cloud sync & backup preferences",
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = handleBack,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
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
                    HorizontalDivider(
                        color = BentoBorder.copy(alpha = 0.22f),
                        thickness = 0.75.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Group 1: Profile
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccountSectionLabel("PROFILE")
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = BentoCardSurface,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    contentAlignment = Alignment.BottomEnd,
                                    modifier = Modifier.size(92.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = BentoPrimary.copy(alpha = 0.16f),
                                        border = BorderStroke(1.5.dp, BentoPrimary.copy(alpha = 0.45f)),
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
                                                    fontSize = 34.sp,
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
                                                    modifier = Modifier.size(54.dp)
                                                )
                                            }
                                        }
                                    }

                                    if (userAccount != null) {
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
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = effectiveDisplayName,
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (userAccount != null) {
                                        IconButton(
                                            onClick = {
                                                editedName = effectiveDisplayName
                                                showEditNameDialog = true
                                            },
                                            modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit name",
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = userAccount?.email ?: "Offline Guest Account",
                                    fontSize = 13.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = BentoPrimary.copy(alpha = 0.14f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
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
                                                fontWeight = FontWeight.SemiBold,
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
                                                color = BentoTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Group 2: Cloud Sync & Backup
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccountSectionLabel("CLOUD SYNCHRONIZATION & BACKUP")
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
                            onOpenAuth = onOpenAuth,
                            onSignInGoogle = onSignInGoogle
                        )
                    }

                    // Group 3: Account Details
                    if (userAccount != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccountSectionLabel("ACCOUNT DETAILS")
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = BentoCardSurface,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(userAccount.uid))
                                            Toast.makeText(context, "User ID copied", Toast.LENGTH_SHORT).show()
                                        }
                                        .defaultMinSize(minHeight = 56.dp)
                                        .padding(horizontal = 18.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = "User Identifier (UID)",
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextPrimary
                                        )
                                        Text(
                                            text = userAccount.uid,
                                            fontSize = 11.5.sp,
                                            color = BentoTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy UID",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Primary Account Action
                    if (userAccount != null) {
                        OutlinedButton(
                            onClick = { showSignOutConfirmDialog = true },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFF5252)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sign Out",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.5.sp
                            )
                        }
                    } else {
                        Button(
                            onClick = { showSignInConfirmDialog = true },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BentoPrimary,
                                contentColor = BentoOnPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sign In or Connect Account",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.5.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Edit Name Dialog
        if (showEditNameDialog) {
            AlertDialog(
                onDismissRequest = { showEditNameDialog = false },
                containerColor = BentoCardSurface,
                shape = RoundedCornerShape(24.dp),
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
                        shape = RoundedCornerShape(14.dp),
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
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                    ) {
                        Text("Save", color = BentoOnPrimary, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEditNameDialog = false }) {
                        Text("Cancel", color = BentoTextSecondary)
                    }
                }
            )
        }

        // Sign In Confirmation Dialog
        if (showSignInConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showSignInConfirmDialog = false },
                containerColor = BentoCardSurface,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = "Sign In or Connect Account?",
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                },
                text = {
                    Text(
                        text = "Would you like to sign in to your account?\n\nYour existing local music library, offline playlists, favorites, and settings will remain intact on your device.",
                        fontSize = 13.5.sp,
                        color = BentoTextSecondary,
                        lineHeight = 18.5.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showSignInConfirmDialog = false
                            onOpenAuth()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                    ) {
                        Text("Continue", color = BentoOnPrimary, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSignInConfirmDialog = false }) {
                        Text("Cancel", color = BentoTextSecondary)
                    }
                }
            )
        }

        // Sign Out Confirmation Dialog
        if (showSignOutConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showSignOutConfirmDialog = false },
                containerColor = BentoCardSurface,
                shape = RoundedCornerShape(24.dp),
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
                        lineHeight = 18.5.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showSignOutConfirmDialog = false
                            onSignOut()
                            Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    ) {
                        Text("Sign Out", color = Color.White, fontWeight = FontWeight.SemiBold)
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
}

@Composable
private fun AccountSectionLabel(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(BentoPrimary)
        )
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            color = BentoTextSecondary
        )
    }
}
