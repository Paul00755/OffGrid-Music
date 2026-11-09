package com.example.ui.screens

import android.content.ContentResolver
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.data.model.Song
import com.example.jam.JamAccessMode
import com.example.jam.JamFeatureController
import com.example.jam.JamFeature
import com.example.jam.JamFeatureRequest
import com.example.jam.JamIncomingVoteAlert
import com.example.jam.JamDownloadState
import com.example.jam.JamPage
import com.example.jam.JamParticipant
import com.example.jam.JamLibraryIndexStatus
import com.example.jam.JamSharedLibraryMatch
import com.example.jam.JamPermissionKind
import com.example.jam.JamPermissionRule
import com.example.jam.JamPermissions
import com.example.jam.JamRecommendation
import com.example.jam.JamRecommendationType
import com.example.jam.JamRepository
import com.example.jam.JamVote
import com.example.jam.JamQueueItem
import com.example.jam.JamSession
import com.example.jam.JamUiState
import com.example.jam.extractJamInviteCode
import com.example.ui.components.AlbumArtView
import com.example.ui.components.JamQrScannerDialog
import com.example.ui.components.rememberReorderableLazyListState
import com.example.ui.components.reorderableDragHandle
import com.example.ui.theme.BentoCardBorder
import com.example.ui.theme.BentoCardPurple
import com.example.ui.theme.BentoCardSurface
import com.example.ui.theme.BentoOnPrimary
import com.example.ui.theme.BentoPrimary
import com.example.ui.theme.BentoScreenBackground
import com.example.ui.theme.BentoTextPrimary
import com.example.ui.theme.BentoTextSecondary
import com.example.ui.theme.GlassGradientBackdrop
import com.example.ui.theme.LocalGlassThemeConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamScreen(
    controller: JamFeatureController?,
    songs: List<Song>,
    onRequireAuthentication: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val page = controller?.page?.collectAsStateWithLifecycle()?.value ?: JamPage.LANDING
    val state = controller?.uiState?.collectAsStateWithLifecycle()?.value ?: JamUiState.Error("Jam couldn't connect to Firebase.")
    val session = controller?.session?.collectAsStateWithLifecycle()?.value
    val participants = controller?.participants?.collectAsStateWithLifecycle()?.value.orEmpty()
    val queue = controller?.queue?.collectAsStateWithLifecycle()?.value.orEmpty()
    val recommendations = controller?.recommendations?.collectAsStateWithLifecycle()?.value.orEmpty()
    val pendingVotes = controller?.pendingVotes?.collectAsStateWithLifecycle()?.value.orEmpty()
    val incomingVoteAlert = controller?.incomingVoteAlert?.collectAsStateWithLifecycle()?.value
    val featureRequests = controller?.featureRequests?.collectAsStateWithLifecycle()?.value.orEmpty()
    val selectedRecommendationId = controller?.selectedRecommendationId?.collectAsStateWithLifecycle()?.value
    val invite = controller?.invite?.collectAsStateWithLifecycle()?.value
    val localTrackAvailable = controller?.localTrackAvailable?.collectAsStateWithLifecycle()?.value ?: false
    val sharedLibraryMatches = controller?.sharedLibraryMatches?.collectAsStateWithLifecycle()?.value.orEmpty()
    val libraryIndexStatus = controller?.libraryIndexStatus?.collectAsStateWithLifecycle()?.value ?: JamLibraryIndexStatus()
    val currentUid = controller?.currentUid?.collectAsStateWithLifecycle()?.value
    val isSignedIn = !currentUid.isNullOrBlank()
    val clipboard = LocalClipboardManager.current
    val glass = LocalGlassThemeConfig.current
    var permissionDraft by remember(session?.id, session?.revision) { mutableStateOf(session?.permissions ?: JamPermissions()) }
    var selectedPreset by rememberSaveable { mutableStateOf("Collaborative") }
    var maxParticipants by rememberSaveable { mutableStateOf(JamRepository.MAX_PARTICIPANTS) }
    var howItWorksStep by rememberSaveable { mutableStateOf<Int?>(null) }
    var showRoomLeaveConfirmation by rememberSaveable { mutableStateOf(false) }
    var participantUidToRemove by rememberSaveable { mutableStateOf<String?>(null) }
    var showNotificationRationale by remember { mutableStateOf(false) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionDenied = !granted
    }

    LaunchedEffect(session?.id) {
        if (session == null) return@LaunchedEffect
        val prefs = context.getSharedPreferences("offgrid_jam_notifications", android.content.Context.MODE_PRIVATE)
        val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val permissionGranted = !permissionRequired ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notificationPermissionDenied = !permissionGranted
        if (!permissionGranted && !prefs.getBoolean("rationale_seen", false)) {
            showNotificationRationale = true
        }
    }

    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = {
                showNotificationRationale = false
                context.getSharedPreferences("offgrid_jam_notifications", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("rationale_seen", true).apply()
            },
            title = { Text("Stay connected to your Jam") },
            text = { Text("Receive song recommendation updates when OffGrid Music is in the background. Voting windows last 30 seconds. Notifications are optional; Jam playback still works if you decline.") },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    context.getSharedPreferences("offgrid_jam_notifications", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("rationale_seen", true).apply()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    notificationPermissionDenied = true
                    context.getSharedPreferences("offgrid_jam_notifications", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("rationale_seen", true).apply()
                }) { Text("Not now") }
            }
        )
    }

    BackHandler(enabled = page != JamPage.LANDING) {
        when (page) {
            JamPage.ACTIVE -> if (session == null) controller?.openPage(JamPage.LANDING) else showRoomLeaveConfirmation = true
            JamPage.INVITE -> showRoomLeaveConfirmation = true
            JamPage.CREATE, JamPage.JOIN -> controller?.openPage(JamPage.LANDING)
            JamPage.PERMISSIONS -> controller?.openPage(JamPage.ACTIVE)
            else -> controller?.openPage(JamPage.LANDING)
        }
    }

    if (howItWorksStep != null) {
        JamHowItWorksDialog(
            currentStep = howItWorksStep!!.coerceIn(0, JAM_HOW_IT_WORKS_STEPS.lastIndex),
            onStepChange = { howItWorksStep = it.coerceIn(0, JAM_HOW_IT_WORKS_STEPS.lastIndex) },
            onDismiss = { howItWorksStep = null }
        )
    }
    if (showRoomLeaveConfirmation && page in setOf(JamPage.ACTIVE, JamPage.INVITE) && session != null) {
        RoomLeaveDialog(
            isHost = session.hostUid == currentUid,
            participants = participants,
            currentUid = currentUid.orEmpty(),
            isWaitingLobby = page == JamPage.INVITE,
            onDismiss = { showRoomLeaveConfirmation = false },
            onLeave = {
                showRoomLeaveConfirmation = false
                controller?.leaveJam()
            },
            onDisband = {
                showRoomLeaveConfirmation = false
                controller?.closeJam()
            }
        )
    }
    participants.firstOrNull { it.uid == participantUidToRemove }?.let { participant ->
        val removalClosesJam = participants.size <= 2
        AlertDialog(
            onDismissRequest = { participantUidToRemove = null },
            title = { Text(if (removalClosesJam) "Remove listener and close Jam?" else "Remove listener?") },
            text = {
                Text(
                    if (removalClosesJam) {
                        "A Jam session needs at least two people to continue. If you remove ${participant.displayName}, the room will close automatically. They will not be able to rejoin this Jam room."
                    } else {
                        "Note: removing ${participant.displayName} from the Jam means they can no longer join this existing Jam room."
                    },
                    color = BentoTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uid = participant.uid
                        participantUidToRemove = null
                        controller?.removeParticipant(uid)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (removalClosesJam) "Remove and close room" else "Confirm & remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { participantUidToRemove = null }) {
                    Text(if (removalClosesJam) "Keep user and continue" else "Cancel", color = BentoPrimary)
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (glass.gradientEnabled) {
            GlassGradientBackdrop()
        } else {
            Box(Modifier.fillMaxSize().background(BentoScreenBackground))
        }

        when (page) {
            JamPage.LANDING -> LandingPage(
                controller = controller,
                onRequireAuthentication = onRequireAuthentication,
                isSignedIn = isSignedIn,
                onHowItWorks = { howItWorksStep = 0 },
                state = state,
                modifier = Modifier.fillMaxSize()
            )
            JamPage.CREATE -> CreatePage(
                permissions = permissionDraft,
                selectedPreset = selectedPreset,
                onPresetChange = { preset ->
                    selectedPreset = preset
                    permissionDraft = permissionForPreset(preset)
                },
                maxParticipants = maxParticipants,
                onMaxParticipantsChange = { maxParticipants = it },
                onPermissionsChange = { permissionDraft = it },
                onBack = { controller?.openPage(JamPage.LANDING) },
                onCreate = { controller?.createJam(permissionDraft, maxParticipants) },
                state = state
            )
            JamPage.JOIN -> JoinPage(
                onBack = { controller?.openPage(JamPage.LANDING) },
                onJoin = { code ->
                    if (controller?.requestJoin(code) == true) onRequireAuthentication()
                },
                state = state
            )
            JamPage.INVITE -> InvitePage(
                invite = invite,
                participants = participants,
                isHost = session?.hostUid != null && session.hostUid == currentUid,
                currentUid = currentUid.orEmpty(),
                maxParticipants = session?.maxParticipants ?: maxParticipants,
                onBackToJam = { controller?.startJam() },
                onCloseRoom = { showRoomLeaveConfirmation = true },
                onRemoveParticipant = { participantUidToRemove = it },
                onCopyCode = { code -> clipboard.setText(AnnotatedString(code)) },
                onShare = { url, code ->
                    val text = url ?: "Join my OffGrid Jam with invitation code $code"
                    runCatching {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }, "Share OffGrid Jam"))
                    }
                },
                state = state
            )
            JamPage.ACTIVE -> ActivePage(
                controller = controller,
                session = session,
                participants = participants,
                queue = queue,
                recommendations = recommendations,
                pendingVotes = pendingVotes,
                incomingVoteAlert = incomingVoteAlert,
                featureRequests = featureRequests,
                selectedRecommendationId = selectedRecommendationId,
                songs = songs,
                localTrackAvailable = localTrackAvailable,
                sharedLibraryMatches = sharedLibraryMatches,
                libraryIndexStatus = libraryIndexStatus,
                currentUid = currentUid.orEmpty(),
                notificationsDenied = notificationPermissionDenied,
                onNotificationSettings = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                },
                state = state,
                onPermissions = {
                    permissionDraft = session?.permissions ?: JamPermissions()
                    controller?.openPage(JamPage.PERMISSIONS)
                },
                onRequestFeature = { feature -> controller?.requestFeature(feature) },
                onResolveFeatureRequest = { feature, enable, block -> controller?.resolveFeatureRequest(feature, enable, block) },
                onRemoveParticipant = { participantUidToRemove = it },
                onBackRequest = { showRoomLeaveConfirmation = true }
            )
            JamPage.PERMISSIONS -> PermissionsPage(
                permissions = permissionDraft,
                participants = participants,
                isHost = session?.hostUid != null && session.hostUid == currentUid,
                onPermissionsChange = { permissionDraft = it },
                onBack = { controller?.openPage(JamPage.ACTIVE) },
                onSave = {
                    controller?.setPermissions(permissionDraft)
                    controller?.openPage(JamPage.ACTIVE)
                },
                state = state
            )
        }
    }
}

@Composable
private fun LandingPage(
    controller: JamFeatureController?,
    onRequireAuthentication: () -> Unit,
    isSignedIn: Boolean,
    onHowItWorks: () -> Unit,
    state: JamUiState,
    modifier: Modifier = Modifier
) {
    val columnState = rememberScrollState()
    Column(
        modifier = modifier.verticalScroll(columnState).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        JamHeader(title = "OffGrid Jam", subtitle = "Listen together, on your own music files")
        JamCard {
            Icon(Icons.Default.Groups, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(38.dp))
            Spacer(Modifier.height(12.dp))
            Text("One queue. Everyone in sync.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
            Text(
                "Create a listening room, invite up to ${JamRepositoryLimit.PARTICIPANTS} people, and play tracks each person already has on their device.",
                color = BentoTextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        TextButton(onClick = onHowItWorks, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("How it works?", color = BentoPrimary, fontWeight = FontWeight.SemiBold)
        }
        JamButton(
            text = "Create Jam",
            icon = Icons.Default.Add,
            enabled = state !is JamUiState.Loading,
            onClick = {
                if (controller?.requestCreate() == true) onRequireAuthentication()
            }
        )
        OutlinedButton(
            onClick = { if (controller?.requestJoin() == true) onRequireAuthentication() },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, BentoCardBorder)
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Join a Jam", fontWeight = FontWeight.SemiBold)
        }
        if (!isSignedIn) {
            JamCard {
                Icon(Icons.Default.Lock, contentDescription = null, tint = BentoPrimary)
                Text("Sign in required", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                Text("Jam uses your existing OffGrid account. Guest music scanning, local playlists, and regular playback remain available.", color = BentoTextSecondary)
            }
        }
        JamStatus(state)
    }
}

@Composable
private fun CreatePage(
    permissions: JamPermissions,
    selectedPreset: String,
    onPresetChange: (String) -> Unit,
    maxParticipants: Int,
    onMaxParticipantsChange: (Int) -> Unit,
    onPermissionsChange: (JamPermissions) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    state: JamUiState
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JamHeader("Create a Jam", "Choose who can control the session", onBack)
        JamCard {
            Text("Room size", style = MaterialTheme.typography.titleMedium, color = BentoTextPrimary, fontWeight = FontWeight.Bold)
            Text("Choose the maximum number of people, including you as host.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { menuExpanded = true }, border = BorderStroke(1.dp, BentoCardBorder)) {
                    Icon(Icons.Default.Groups, contentDescription = null, tint = BentoPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("$maxParticipants people", color = BentoTextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("⌄", color = BentoTextSecondary)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    (2..JamRepository.MAX_PARTICIPANTS).forEach { count ->
                        DropdownMenuItem(
                            text = { Text("$count people") },
                            onClick = {
                                onMaxParticipantsChange(count)
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Host Controlled", "Open Listening", "Collaborative", "Custom").forEach { preset ->
                FilterChip(selected = selectedPreset == preset, onClick = { onPresetChange(preset) }, label = { Text(preset, maxLines = 1) })
            }
        }
        JamCard {
            Text("Playback and queue permissions", style = MaterialTheme.typography.titleMedium, color = BentoTextPrimary, fontWeight = FontWeight.Bold)
            Text("Adding tracks and managing the queue are separate permissions.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
            PermissionRows(permissions = permissions, onChange = onPermissionsChange, allowSelected = false)
        }
        JamCard {
            JamFeatureToggles(
                permissions,
                onPermissionsChange,
                enabled = true
            )
        }
        JamButton("Create Jam", Icons.Default.Groups, enabled = state !is JamUiState.Loading, onClick = onCreate)
        JamStatus(state)
    }
}

@Composable
private fun JoinPage(onBack: () -> Unit, onJoin: (String) -> Unit, state: JamUiState) {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var cameraPermissionDenied by rememberSaveable { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraPermissionDenied = false
            showQrScanner = true
        } else {
            cameraPermissionDenied = true
        }
    }

    val launchQrScanner = {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            cameraPermissionDenied = false
            showQrScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (showQrScanner) {
        JamQrScannerDialog(
            onDismiss = { showQrScanner = false },
            onCodeScanned = { scannedCode ->
                showQrScanner = false
                val normalized = extractJamInviteCode(scannedCode)
                if (normalized.length >= 12) {
                    code = normalized
                    onJoin(normalized)
                }
            }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        JamHeader("Join a Jam", "Enter the invitation code or scan the QR code", onBack)
        OutlinedTextField(
            value = code,
            onValueChange = { code = extractJamInviteCode(it) },
            label = { Text("Invitation code or link") },
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = BentoPrimary) },
            trailingIcon = {
                IconButton(
                    onClick = launchQrScanner,
                    modifier = Modifier.testTag("join_code_qr_icon_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan QR code",
                        tint = BentoPrimary
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().testTag("jam_invite_code_input")
        )
        JamButton(
            text = "Continue",
            icon = Icons.Default.PersonAdd,
            enabled = code.length >= 12 && state !is JamUiState.Loading
        ) {
            onJoin(code)
        }
        OutlinedButton(
            onClick = launchQrScanner,
            enabled = state !is JamUiState.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("scan_qr_button"),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, BentoCardBorder)
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = BentoPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Scan QR Code", color = BentoTextPrimary, fontWeight = FontWeight.SemiBold)
        }
        if (cameraPermissionDenied) {
            JamCard {
                Text(
                    "Camera permission is required to scan a Jam QR code.",
                    color = BentoTextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Allow camera access when prompted, or open App Settings if you previously disabled it.",
                    color = BentoTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Allow Camera", color = BentoPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        }
                    }) {
                        Text("Open Settings", color = BentoTextSecondary)
                    }
                }
            }
        }
        Text(
            "You can enter the 16-character invitation code, paste an invitation link, or tap Scan QR Code to scan the host's QR directly.",
            color = BentoTextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        JamStatus(state)
    }
}

@Composable
private fun InvitePage(
    invite: com.example.jam.JamInvite?,
    participants: List<JamParticipant>,
    isHost: Boolean,
    currentUid: String,
    maxParticipants: Int,
    onBackToJam: () -> Unit,
    onCloseRoom: () -> Unit,
    onRemoveParticipant: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onShare: (String?, String) -> Unit,
    state: JamUiState
) {
    var showAllParticipants by rememberSaveable(invite?.sessionId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        JamHeader("Invite people", "Share this code or link with your group")
        if (state is JamUiState.Loading) {
            CircularProgressIndicator(color = BentoPrimary)
        } else {
            if (invite == null) {
                JamCard {
                    Icon(Icons.Default.Link, contentDescription = null, tint = BentoPrimary)
                    Text("Invitation code unavailable", color = BentoTextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("This saved room is still waiting for listeners, but its old invitation code is no longer on this device. Close this room or start a new one to share a fresh code.", color = BentoTextSecondary)
                }
            } else {
                JamCard {
                    Text("INVITATION CODE", style = MaterialTheme.typography.labelLarge, color = BentoTextSecondary, letterSpacing = 1.sp)
                    Text(invite.code.chunked(4).joinToString(" "), style = MaterialTheme.typography.headlineMedium, color = BentoPrimary, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { onCopyCode(invite.code) }, border = BorderStroke(1.dp, BentoCardBorder)) {
                        Icon(Icons.Default.ContentCopy, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy code")
                    }
                }
                if (invite.url != null) {
                    JamCard {
                        Text("INVITATION LINK", style = MaterialTheme.typography.labelLarge, color = BentoTextSecondary, letterSpacing = 1.sp)
                        Text(invite.url, color = BentoTextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        JamQrCode(invite.url)
                        OutlinedButton(onClick = { onShare(invite.url, invite.code) }, border = BorderStroke(1.dp, BentoCardBorder)) {
                            Icon(Icons.Default.QrCode2, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Share link or QR")
                        }
                    }
                } else {
                    JamCard {
                        Icon(Icons.Default.Link, contentDescription = null, tint = BentoPrimary)
                        Text("Invitation links need a configured domain", color = BentoTextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("The code is ready to share. Configure JAM_INVITE_HOST and Firebase Hosting to enable verified links and QR invitations.", color = BentoTextSecondary)
                    }
                }
            }
            JamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Joined listeners", color = BentoTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${participants.size}/$maxParticipants", color = BentoPrimary, fontWeight = FontWeight.Bold)
                }
                if (participants.isEmpty()) {
                    Text("Waiting for listeners to connect…", color = BentoTextSecondary)
                } else {
                    (if (showAllParticipants) participants else participants.take(4)).forEach { participant ->
                        CompactParticipantRow(participant) {
                            if (isHost && participant.uid != currentUid) {
                                IconButton(onClick = { onRemoveParticipant(participant.uid) }) {
                                    Icon(Icons.Default.RemoveCircleOutline, "Remove ${participant.displayName}", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    if (participants.size > 4) {
                        TextButton(onClick = { showAllParticipants = !showAllParticipants }) {
                            Text(if (showAllParticipants) "Show fewer" else "Show all ${participants.size} listeners", color = BentoPrimary)
                        }
                    }
                }
                if (participants.size < 2) {
                    Text("Wait for at least one other person to join before opening the room.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Everyone is here. Open the room when your group is ready; new people can’t join after it starts.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            JamButton(
                text = if (participants.size < 2) "Waiting for a listener" else "Open Jam",
                icon = Icons.Default.Groups,
                enabled = participants.size >= 2 && state !is JamUiState.Loading,
                onClick = onBackToJam
            )
            OutlinedButton(
                onClick = onCloseRoom,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Close Jam Room", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        }
        JamStatus(state)
    }
}

private data class JamHowItWorksStep(
    val title: String,
    val description: String,
    val previewIcons: List<androidx.compose.ui.graphics.vector.ImageVector>
)

private val JAM_HOW_IT_WORKS_STEPS = listOf(
    JamHowItWorksStep(
        "Create a room",
        "The host chooses a room size and playback permissions. At least one other listener must join before the host can open the room.",
        listOf(Icons.Default.Groups, Icons.Default.Tune, Icons.Default.Check)
    ),
    JamHowItWorksStep(
        "Invite your group",
        "Share the invitation code, QR, or link. Everyone needs OffGrid Music and an internet connection to sign in and join before the host starts listening.",
        listOf(Icons.Default.Link, Icons.Default.PersonAdd, Icons.Default.Groups)
    ),
    JamHowItWorksStep(
        "Find music you can all play",
        "Add a track from your library. Jam checks each device locally; if a listener doesn’t have that audio, optional local-song sharing can send it after everyone approves.",
        listOf(Icons.Default.LibraryMusic, Icons.Default.QueueMusic, Icons.Default.MusicNote)
    ),
    JamHowItWorksStep(
        "Listen together",
        "Jam keeps one shared queue and synchronizes play, pause, seek, and track changes. Join everyone before playback starts; late joins are blocked for that session.",
        listOf(Icons.Default.QueueMusic, Icons.Default.PlayArrow, Icons.Default.Groups)
    ),
    JamHowItWorksStep(
        "Share audio directly",
        "When the host enables local-song sharing, a listener can choose a supported audio file (up to 25 MB). After unanimous approval it transfers device-to-device; the audio is not stored in Firebase. Internet is required for Jam coordination, while a direct transfer may use a compatible local network or internet route.",
        listOf(Icons.Default.MusicNote, Icons.Default.Check, Icons.Default.Groups)
    )
)

@Composable
private fun JamHowItWorksDialog(
    currentStep: Int,
    onStepChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val step = JAM_HOW_IT_WORKS_STEPS[currentStep]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How OffGrid Jam works") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                JamCard {
                    JamHowItWorksPreview(step.previewIcons)
                    Text("STEP ${currentStep + 1} OF ${JAM_HOW_IT_WORKS_STEPS.size}", color = BentoPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(step.title, color = BentoTextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(step.description, color = BentoTextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onStepChange(currentStep - 1) }, enabled = currentStep > 0) {
                        Text("Previous", color = if (currentStep > 0) BentoPrimary else BentoTextSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${currentStep + 1} / ${JAM_HOW_IT_WORKS_STEPS.size}", color = BentoTextSecondary, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onStepChange(currentStep + 1) }, enabled = currentStep < JAM_HOW_IT_WORKS_STEPS.lastIndex) {
                        Text("Next", color = if (currentStep < JAM_HOW_IT_WORKS_STEPS.lastIndex) BentoPrimary else BentoTextSecondary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = BentoPrimary) } }
    )
}

@Composable
private fun JamHowItWorksPreview(icons: List<androidx.compose.ui.graphics.vector.ImageVector>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icons.forEachIndexed { index, image ->
            if (index > 0) {
                Text("→", color = BentoTextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Surface(shape = CircleShape, color = BentoPrimary.copy(alpha = 0.16f), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(image, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun RoomLeaveDialog(
    isHost: Boolean,
    participants: List<JamParticipant>,
    currentUid: String,
    isWaitingLobby: Boolean,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    onDisband: () -> Unit
) {
    val candidates = remember(participants, currentUid) {
        participants.filter { it.uid != currentUid }.sortedBy { it.joinedAtMs }
    }
    val nextHost = candidates.firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(when {
                !isHost -> "Leave this Jam?"
                isWaitingLobby -> "Close the invite room?"
                else -> "Leave as host?"
            })
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isHost) {
                    if (candidates.size >= 2 && nextHost != null) {
                        Text("If you leave, ${nextHost.displayName} will automatically become host because they joined first. Everyone in the room will see a message. The Jam stays open with at least two listeners remaining.", color = BentoTextSecondary)
                    } else if (candidates.size == 1) {
                        Text("Only one listener would remain after you leave, so the Jam will close and notify them.", color = BentoTextSecondary)
                    } else {
                        Text("No listener has joined yet, so leaving closes this Jam room.", color = BentoTextSecondary)
                    }
                } else {
                    Text(
                        if (participants.size <= 2) "You are the only guest. Leaving will close the Jam because only one listener would remain; the host will be notified. Your previous playback will be restored."
                        else "You’ll leave the room and your previous local queue and playback will be restored.",
                        color = BentoTextSecondary
                    )
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isHost && candidates.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onLeave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (candidates.size >= 2) "Leave and hand off to first listener" else "Leave and close the Jam")
                    }
                }
                if (isHost && candidates.size >= 2) {
                    Button(onClick = onDisband, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Disband room for everyone")
                    }
                } else if (isHost && candidates.isEmpty()) {
                    Button(onClick = onLeave, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Close Jam Room")
                    }
                } else {
                    if (!isHost) Button(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Leave Jam") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Stay in Jam", color = BentoPrimary) }
            }
        }
    )
}

@Composable
private fun ActivePage(
    controller: JamFeatureController?,
    session: JamSession?,
    participants: List<JamParticipant>,
    queue: List<JamQueueItem>,
    recommendations: List<JamRecommendation>,
    pendingVotes: Map<String, JamVote>,
    incomingVoteAlert: JamIncomingVoteAlert?,
    featureRequests: List<JamFeatureRequest>,
    selectedRecommendationId: String?,
    songs: List<Song>,
    localTrackAvailable: Boolean,
    sharedLibraryMatches: List<JamSharedLibraryMatch>,
    libraryIndexStatus: JamLibraryIndexStatus,
    currentUid: String,
    notificationsDenied: Boolean,
    onNotificationSettings: () -> Unit,
    state: JamUiState,
    onPermissions: () -> Unit,
    onRequestFeature: (JamFeature) -> Unit,
    onResolveFeatureRequest: (JamFeature, Boolean, Boolean) -> Unit,
    onRemoveParticipant: (String) -> Unit,
    onBackRequest: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var showAllMatchesDialog by rememberSaveable { mutableStateOf(false) }
    var localShareConsent by rememberSaveable { mutableStateOf(false) }
    var localShareMode by rememberSaveable { mutableStateOf(JamLocalShareMode.IN_APP_LIBRARY) }
    var queueExpanded by rememberSaveable { mutableStateOf(true) }
    var localFileError by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLocalAudio by remember { mutableStateOf<LocalAudioSelection?>(null) }
    var selectedInAppShare by remember { mutableStateOf<InAppLibraryAudioSelection?>(null) }
    val localAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            coroutineScope.launch {
                runCatching { withContext(Dispatchers.IO) { readLocalAudioMetadata(context.contentResolver, uri, context.cacheDir) } }
                    .onSuccess { selected ->
                        if (queue.any { it.track.sharedTrackId == selected.track.sharedTrackId }) {
                            localFileError = "This song is already in the Jam shared library."
                        } else {
                            selectedLocalAudio = selected
                            selectedInAppShare = null
                            localFileError = null
                        }
                    }
                    .onFailure { localFileError = it.message ?: "This audio file couldn't be read." }
            }
        }
    }
    var sliderPosition by remember(session?.playback?.revision) {
        mutableStateOf(session?.playback?.estimatedPosition()?.toFloat() ?: 0f)
    }
    var isSeeking by remember { mutableStateOf(false) }
    val currentItem = queue.firstOrNull { it.id == session?.playback?.queueItemId }
        ?: session?.playback?.sharedTrackId?.takeIf(String::isNotBlank)?.let { tid ->
            queue.firstOrNull { it.track.sharedTrackId == tid }
        }
        ?: queue.firstOrNull()
    val isHost = session?.hostUid != null && session.hostUid == currentUid
    val hostUid = session?.hostUid.orEmpty()
    val roomStarted = session?.started == true
    val permissionUid = currentUid
    val permissions = session?.permissions ?: JamPermissions()
    val canPlayPause = permissions.allows(JamPermissionKind.PLAY_PAUSE, permissionUid, hostUid)
    val canSeek = permissions.allows(JamPermissionKind.SEEK, permissionUid, hostUid)
    val canSkip = permissions.allows(JamPermissionKind.SKIP_TRACK, permissionUid, hostUid)
    val canAddTracks = permissions.allows(JamPermissionKind.ADD_TO_QUEUE, permissionUid, hostUid)
    val canManageQueue = permissions.allows(JamPermissionKind.MANAGE_QUEUE, permissionUid, hostUid)
    // Switching to a different track in the queue requires Play/Pause, Skip, AND Queue Control permissions
    val canSwitchQueueTrack = roomStarted && canPlayPause && canSkip && canManageQueue
    // Playing a matched library song immediately requires Add Tracks, Play/Pause, AND Skip permissions
    val canPlayMatchedNow = roomStarted && canAddTracks && canPlayPause && canSkip
    val hasDuplicateQueueTracks = remember(queue) {
        queue.map { it.track.sharedTrackId }.distinct().size < queue.size
    }

    val durationMs = currentItem?.track?.durationMs?.coerceAtLeast(1L) ?: 1L
    var selectedRoomTab by rememberSaveable(session?.id) { mutableStateOf(0) }
    var participantsExpanded by rememberSaveable { mutableStateOf(false) }
    var requestClockMs by remember(session?.id) { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedFeatureRequest by remember { mutableStateOf<JamFeatureRequest?>(null) }
    var blockFeatureRequester by remember { mutableStateOf(false) }

    // Local reorderable state for smooth drag-to-reorder with auto-scroll in Shared Queue
    var displayedQueue by remember { mutableStateOf(queue) }
    val queueListState = rememberLazyListState()
    val queueRowHeightPx = with(density) { 68.dp.toPx() }
    val reorderState = rememberReorderableLazyListState(
        listState = queueListState,
        itemHeightPx = queueRowHeightPx,
        headerItemCount = 0,
        onMove = { fromIndex, toIndex ->
            if (fromIndex in displayedQueue.indices && toIndex in displayedQueue.indices && fromIndex != toIndex) {
                val mutable = displayedQueue.toMutableList()
                val moved = mutable.removeAt(fromIndex)
                mutable.add(toIndex, moved)
                displayedQueue = mutable
            }
        },
        onDragEnd = { _, _ ->
            controller?.reorderQueueIds(displayedQueue.map { it.id })
        }
    )
    LaunchedEffect(queue, reorderState.isDragging) {
        if (!reorderState.isDragging) {
            displayedQueue = queue
        }
    }

    LaunchedEffect(selectedRecommendationId) {
        if (!selectedRecommendationId.isNullOrBlank()) {
            val targetRec = recommendations.firstOrNull { it.id == selectedRecommendationId }
            selectedRoomTab = if (targetRec?.type == JamRecommendationType.LOCAL_SONG) 2 else 1
        }
    }

    LaunchedEffect(session?.id) {
        while (session != null) {
            requestClockMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val activeFeatureRequests = remember(featureRequests, requestClockMs) {
        featureRequests.filter { it.isPending(requestClockMs) }
    }
    val noMatchesFound = (libraryIndexStatus.isReady && sharedLibraryMatches.isEmpty() && queue.isEmpty()) ||
        (currentItem != null && !localTrackAvailable)

    // Standard preview shows up to 50 matching songs as requested by the user
    val visibleMatches = remember(sharedLibraryMatches, libraryQuery) {
        val query = libraryQuery.trim()
        if (query.isBlank()) {
            sharedLibraryMatches.take(50)
        } else {
            sharedLibraryMatches.asSequence()
                .filter { match ->
                    match.song.displayTitle.contains(query, true) ||
                        match.song.displayArtist.contains(query, true) ||
                        match.song.displayAlbum.contains(query, true)
                }
                .take(50)
                .toList()
        }
    }

    LaunchedEffect(session?.playback, isSeeking, durationMs) {
        while (!isSeeking) {
            val playback = session?.playback ?: break
            sliderPosition = playback.estimatedPosition().toFloat().coerceIn(0f, durationMs.toFloat())
            kotlinx.coroutines.delay(200L)
        }
    }

    if (showAllMatchesDialog) {
        AllMatchingSongsDialog(
            matches = sharedLibraryMatches,
            canAddTracks = canAddTracks,
            canPlayNow = canPlayMatchedNow,
            canRecommend = permissions.allowSharedLibraryRecommendations,
            isHost = isHost,
            onAddAll = { shuffle -> controller?.addAllMatchingSongsToQueue(shuffle = shuffle) },
            onPlayNow = { match -> controller?.playSharedLibrarySongNow(match) },
            onPlayNext = { match -> controller?.playSharedLibrarySongNext(match) },
            onAddToQueue = { match -> controller?.addSharedLibrarySongToQueue(match) },
            onRecommend = { match -> controller?.recommendSharedLibrarySong(match) },
            onOpenPermissions = onPermissions,
            onDismiss = { showAllMatchesDialog = false }
        )
    }

    selectedFeatureRequest?.takeIf { it.isPending(requestClockMs) }?.let { request ->
        AlertDialog(
            onDismissRequest = { selectedFeatureRequest = null; blockFeatureRequester = false },
            title = { Text("Respond to Request") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${request.requesterName} asked you to enable ${request.feature.title} for this Jam.",
                        color = BentoTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Expires in ${formatCountdownMmSs(request.remainingMs(requestClockMs))}. Turning one setting on or off will not affect the other feature.",
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = blockFeatureRequester, onCheckedChange = { blockFeatureRequester = it })
                        Text(
                            text = "Block ${request.requesterName} from requesting this feature again in this Jam",
                            color = BentoTextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            onResolveFeatureRequest(request.feature, true, blockFeatureRequester)
                            selectedFeatureRequest = null
                            blockFeatureRequester = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                    ) { Text("Enable Feature", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = {
                            onResolveFeatureRequest(request.feature, false, blockFeatureRequester)
                            selectedFeatureRequest = null
                            blockFeatureRequester = false
                        },
                        border = BorderStroke(1.dp, BentoCardBorder)
                    ) { Text("Keep Current Setting", color = BentoTextPrimary) }
                    TextButton(onClick = { selectedFeatureRequest = null; blockFeatureRequester = false }) {
                        Text("Cancel", color = BentoTextSecondary)
                    }
                }
            }
        )
    }

    val sharedRecommendations = remember(recommendations) {
        recommendations.filter { it.type == JamRecommendationType.SHARED_LIBRARY }
    }
    val localShareRecommendations = remember(recommendations) {
        recommendations.filter { it.type == JamRecommendationType.LOCAL_SONG }
    }
    val activeVotesBadgeCount = remember(recommendations) {
        recommendations.count { it.isVoting }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (notificationsDenied) item {
            JamCard {
                Text("Background Jam notifications are off. You can still vote while the Jam screen is open.", color = BentoTextSecondary)
                TextButton(onClick = onNotificationSettings) { Text("Notification settings", color = BentoPrimary) }
            }
        }
        item {
            val hostName = participants.firstOrNull { it.uid == hostUid || it.isHost }?.displayName ?: "Host"
            JamHeader(
                title = "OffGrid Jam",
                subtitle = if (session == null) {
                    "Connecting to your session…"
                } else {
                    "${session.participantCount}/${session.maxParticipants} listeners · Host: $hostName · ${if (roomStarted) "Connected" else "Waiting"}"
                },
                onBack = onBackRequest,
                trailing = {
                    if (isHost) {
                        IconButton(onClick = onPermissions) {
                            Icon(Icons.Default.Tune, "Permissions", tint = BentoPrimary)
                        }
                    }
                }
            )
        }
        if (incomingVoteAlert != null) {
            item(key = "incoming-vote-${incomingVoteAlert.recommendationId}") {
                JamIncomingVoteBanner(
                    alert = incomingVoteAlert,
                    isSubmittingVote = pendingVotes.containsKey(incomingVoteAlert.recommendationId),
                    onAccept = { controller?.vote(incomingVoteAlert.recommendationId, JamVote.ACCEPT) },
                    onReject = { controller?.vote(incomingVoteAlert.recommendationId, JamVote.REJECT) },
                    onView = {
                        selectedRoomTab = if (incomingVoteAlert.isLocalSong) 2 else 1
                        controller?.dismissIncomingVoteAlert()
                    },
                    onDismiss = { controller?.dismissIncomingVoteAlert() }
                )
            }
        }
        if (activeFeatureRequests.isNotEmpty()) {
            itemsIndexed(activeFeatureRequests, key = { _, request -> "request-${request.feature.documentId}" }) { _, request ->
                JamFeatureRequestBanner(
                    request = request,
                    remainingMs = request.remainingMs(requestClockMs),
                    isHost = isHost,
                    onRespond = { selectedFeatureRequest = request; blockFeatureRequester = false },
                    onIgnore = { onResolveFeatureRequest(request.feature, false, false) }
                )
            }
        }
        if (noMatchesFound) item {
            JamCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.MusicNote, null, tint = BentoPrimary, modifier = Modifier.size(26.dp))
                    Text("No matches found", color = BentoTextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    if (currentItem != null && !localTrackAvailable) {
                        "${currentItem.track.title} isn’t available on this device. Share the audio between devices to begin listening together."
                    } else if (!libraryIndexStatus.isReady) {
                        "Checking the devices for songs everyone already has… ${libraryIndexStatus.indexedParticipants}/${libraryIndexStatus.participantCount} devices ready."
                    } else {
                        "No songs match across everyone’s devices yet. Share some between both devices to begin listening together."
                    },
                    color = BentoTextSecondary
                )
                if (!permissions.allowLocalSongSharing && isHost) {
                    Text("Local song sharing is off for this Jam.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = { controller?.setPermissions(permissions.copy(allowLocalSongSharing = true)) }
                    ) { Text("Turn on local song sharing", color = BentoPrimary, fontWeight = FontWeight.Bold) }
                } else if (permissions.allowLocalSongSharing) {
                    Text("Open the Local Sharing tab below to share an audio file directly with the group.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Synchronized Now Playing Card
        item {
            JamCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val nowPlayingSong = remember(currentItem?.track, sharedLibraryMatches, songs) {
                        currentItem?.track?.let { track ->
                            controller?.resolveSongForTrack(track, songs)
                                ?: resolveSongForTrack(track, sharedLibraryMatches, songs)
                        }
                    }
                    if (nowPlayingSong != null) {
                        AlbumArtView(
                            song = nowPlayingSong,
                            modifier = Modifier.size(72.dp),
                            cornerRadius = 16
                        )
                    } else {
                        Box(
                            Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(BentoCardPurple),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MusicNote, null, tint = BentoPrimary, modifier = Modifier.size(32.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = currentItem?.track?.title ?: "Nothing queued",
                            color = BentoTextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentItem?.track?.artist
                                ?: if (sharedLibraryMatches.isNotEmpty()) "${sharedLibraryMatches.size} matching songs ready to add" else "Add a track from your library",
                            color = BentoTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (currentItem != null && !localTrackAvailable) {
                            Text(
                                text = "This track isn't available on your device",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else if (currentItem != null) {
                            Text(
                                text = if (session?.playback?.isPlaying == true) "Synced live across all devices" else "Paused · Ready to play",
                                color = BentoPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Slider(
                    value = sliderPosition.coerceIn(0f, durationMs.toFloat()),
                    onValueChange = { isSeeking = true; sliderPosition = it },
                    onValueChangeFinished = { controller?.seek(sliderPosition.toLong()); isSeeking = false },
                    valueRange = 0f..durationMs.toFloat(),
                    enabled = roomStarted && currentItem != null && localTrackAvailable && canSeek
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (currentItem != null) formatDurationMmSs(sliderPosition.toLong()) else "0:00",
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = if (currentItem != null) formatDurationMmSs(durationMs) else "0:00",
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(
                        onClick = { controller?.shuffleQueue() },
                        enabled = roomStarted && queue.size > 1 && canManageQueue
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle shared queue",
                            tint = if (roomStarted && queue.size > 1 && canManageQueue) BentoTextPrimary else BentoTextSecondary.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(
                        onClick = { controller?.skip(-1) },
                        enabled = roomStarted && queue.isNotEmpty() && canSkip
                    ) {
                        Icon(Icons.Default.SkipPrevious, "Previous", tint = BentoTextPrimary)
                    }
                    Surface(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .clickable(
                                enabled = roomStarted && (currentItem != null || queue.isNotEmpty()) && localTrackAvailable && canPlayPause
                            ) { controller?.togglePlayback() },
                        color = BentoPrimary,
                        shape = CircleShape
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (session?.playback?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play or pause",
                                tint = BentoOnPrimary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { controller?.skip(1) },
                        enabled = roomStarted && queue.isNotEmpty() && canSkip
                    ) {
                        Icon(Icons.Default.SkipNext, "Next", tint = BentoTextPrimary)
                    }
                    IconButton(
                        onClick = { selectedRoomTab = 0 }
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Open shared queue",
                            tint = if (selectedRoomTab == 0) BentoPrimary else BentoTextSecondary
                        )
                    }
                }
                if (!roomStarted) {
                    Text(
                        text = "Playback starts when the host opens the room.",
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Listeners Accordion
        item {
            JamAccordion(
                title = "Listeners",
                summary = "${participants.size} / ${session?.maxParticipants ?: JamRepository.MAX_PARTICIPANTS}",
                icon = Icons.Default.Groups,
                expanded = participantsExpanded,
                onToggle = { participantsExpanded = !participantsExpanded }
            ) {
                participants.forEach { participant ->
                    CompactParticipantRow(participant) {
                        if (isHost && participant.uid != currentUid) {
                            IconButton(onClick = { onRemoveParticipant(participant.uid) }) {
                                Icon(Icons.Default.RemoveCircleOutline, "Remove ${participant.displayName}", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // 2-Column Bento Feature & Permission Grid (Host can toggle Song Recommendations & Local Sharing independently right here!)
        item {
            JamPermissionsBentoGrid(
                permissions = permissions,
                isHost = isHost,
                onToggleRecommendations = if (isHost) { enabled ->
                    controller?.setPermissions(permissions.copy(allowSharedLibraryRecommendations = enabled))
                } else null,
                onToggleLocalSharing = if (isHost) { enabled ->
                    controller?.setPermissions(permissions.copy(allowLocalSongSharing = enabled))
                } else null,
                onOpenPermissions = if (isHost) onPermissions else null
            )
        }

        // Room Navigation Tabs: Queue | Recommendations | Local Sharing
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val tabLabels = listOf(
                    "Queue (${queue.size})",
                    if (activeVotesBadgeCount > 0) "Recommendations ($activeVotesBadgeCount)" else "Recommendations",
                    "Local Sharing"
                )
                tabLabels.forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedRoomTab == index,
                        onClick = { selectedRoomTab = index },
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selectedRoomTab == index) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }
        }

        // TAB 0: SHARED QUEUE
        if (selectedRoomTab == 0) item {
            JamAccordion(
                title = "Shared Queue",
                summary = "${queue.size} tracks${if (canManageQueue && queue.size > 1) " · Drag ≡ to reorder" else ""}",
                icon = Icons.Default.QueueMusic,
                expanded = queueExpanded,
                onToggle = { queueExpanded = !queueExpanded }
            ) {
                if (canAddTracks && sharedLibraryMatches.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { controller?.addAllMatchingSongsToQueue(shuffle = false) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BentoPrimary),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Include All (${sharedLibraryMatches.size})",
                                color = BentoPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { controller?.addAllMatchingSongsToQueue(shuffle = true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BentoCardBorder),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Shuffle & Include All",
                                color = BentoTextPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Queue management toolbar (Remove Duplicates + Shuffle Queue) like Up-Next Queue
                if (canManageQueue && queue.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { controller?.removeDuplicateSongsFromQueue() },
                            modifier = Modifier.weight(1f).testTag("jam_queue_remove_duplicates_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (hasDuplicateQueueTracks) BentoPrimary else BentoCardBorder),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = if (hasDuplicateQueueTracks) BentoPrimary else BentoTextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Remove Duplicates",
                                color = if (hasDuplicateQueueTracks) BentoPrimary else BentoTextPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { controller?.shuffleQueue() },
                            modifier = Modifier.weight(1f).testTag("jam_queue_shuffle_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BentoCardBorder),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Shuffle Queue",
                                color = BentoTextPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (displayedQueue.isEmpty()) {
                    Text(
                        text = "No songs queued yet. Open Recommendations to add or suggest matching songs, or share a local audio file.",
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    val queueMaxHeight = (displayedQueue.size * 72).coerceIn(140, 440).dp
                    LazyColumn(
                        state = queueListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp, max = queueMaxHeight),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = displayedQueue,
                            key = { _, item -> item.id }
                        ) { index, item ->
                            val isCurrentTrack = item.id == currentItem?.id
                            val isDraggingItem = reorderState.draggedKey == item.id
                            val itemSong = remember(item.track, sharedLibraryMatches, songs) {
                                controller?.resolveSongForTrack(item.track, songs)
                                    ?: resolveSongForTrack(item.track, sharedLibraryMatches, songs)
                            }
                            val canPlayThisRow = if (isCurrentTrack) {
                                roomStarted && canPlayPause
                            } else {
                                canSwitchQueueTrack
                            }
                            val dragModifier = if (canManageQueue && displayedQueue.size > 1) {
                                Modifier.reorderableDragHandle(
                                    state = reorderState,
                                    getIndex = { displayedQueue.indexOfFirst { it.id == item.id }.coerceAtLeast(0) },
                                    getMinIndex = { 0 },
                                    getMaxIndex = { (displayedQueue.size - 1).coerceAtLeast(0) },
                                    getKey = { item.id }
                                )
                            } else null

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (isDraggingItem) 10f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDraggingItem) reorderState.dragOffsetY else 0f
                                        scaleX = if (isDraggingItem) 1.02f else 1f
                                        scaleY = if (isDraggingItem) 1.02f else 1f
                                    }
                            ) {
                                RedesignedQueueRow(
                                    index = index,
                                    item = item,
                                    resolvedSong = itemSong,
                                    isCurrent = isCurrentTrack,
                                    isDragging = isDraggingItem,
                                    canPlay = canPlayThisRow,
                                    canManage = canManageQueue,
                                    canRecommend = permissions.allowSharedLibraryRecommendations,
                                    dragHandleModifier = dragModifier,
                                    onPlayNow = { controller?.playQueueItem(item.id) },
                                    onRecommend = { controller?.recommendSharedTrack(item.track) },
                                    onRemove = { controller?.removeQueueItem(item.id) },
                                    onMoveUp = { controller?.moveQueueItem(item.id, -1) },
                                    onMoveDown = { controller?.moveQueueItem(item.id, 1) },
                                    isFirst = index == 0,
                                    isLast = index == displayedQueue.lastIndex
                                )
                            }
                        }
                    }
                }
            }
        }

        // TAB 1: RECOMMENDATIONS & SHARED LIBRARY MATCHING SONGS
        if (selectedRoomTab == 1) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1) Active Recommendations & Voting Panel FIRST so users never have to drag down to vote!
                if (permissions.allowSharedLibraryRecommendations || isHost) {
                    RichRecommendationPanel(
                        title = "Song Recommendations",
                        recommendations = sharedRecommendations,
                        emptyMessage = if (permissions.allowSharedLibraryRecommendations) {
                            "No song recommendations yet. Pick any matching song below to start a 30-second group vote."
                        } else {
                            "Song recommendations are currently turned off for participants."
                        },
                        participants = participants,
                        currentUid = currentUid,
                        hostUid = hostUid,
                        selectedRecommendationId = selectedRecommendationId,
                        pendingVotes = pendingVotes,
                        songs = songs,
                        sharedLibraryMatches = sharedLibraryMatches,
                        onVote = { recommendationId, vote -> controller?.vote(recommendationId, vote) },
                        onRetryUpload = { controller?.retryUpload(it) },
                        onRetryDownload = { controller?.retryDownload(it) },
                        onViewQueue = { selectedRoomTab = 0 }
                    )
                } else {
                    // Participant view when Song Recommendations is OFF
                    JamFeatureDisabledPromptCard(
                        feature = JamFeature.SHARED_RECOMMENDATIONS,
                        request = activeFeatureRequests.firstOrNull { it.feature == JamFeature.SHARED_RECOMMENDATIONS },
                        requestBlocked = featureRequests.firstOrNull { it.feature == JamFeature.SHARED_RECOMMENDATIONS }?.blockedUids?.contains(currentUid) == true,
                        onRequest = { onRequestFeature(JamFeature.SHARED_RECOMMENDATIONS) }
                    )
                }

                // 2) Matched Songs Library (shows up to 50 songs in standard preview + View All modal + bulk Include All / Shuffle All + per-song action menu)
                if (canAddTracks || permissions.allowSharedLibraryRecommendations || isHost) {
                    JamCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Matched Songs Library",
                                    color = BentoTextPrimary,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when {
                                        libraryIndexStatus.error != null -> libraryIndexStatus.error
                                        !libraryIndexStatus.isReady -> "Matching songs across devices… ${libraryIndexStatus.indexedParticipants}/${libraryIndexStatus.participantCount} devices indexed."
                                        sharedLibraryMatches.isEmpty() -> "No matches found across connected devices yet."
                                        else -> "${sharedLibraryMatches.size} songs available on every connected device (showing up to 50)."
                                    },
                                    color = BentoTextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (sharedLibraryMatches.isNotEmpty()) {
                                TextButton(
                                    onClick = { showAllMatchesDialog = true },
                                    modifier = Modifier.testTag("view_all_matching_songs_button")
                                ) {
                                    Text(
                                        text = "View All (${sharedLibraryMatches.size})",
                                        color = BentoPrimary,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        // Bulk Queue Actions: Include All & Shuffle & Include All
                        if (canAddTracks && sharedLibraryMatches.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { controller?.addAllMatchingSongsToQueue(shuffle = false) },
                                    modifier = Modifier.weight(1f).height(42.dp).testTag("include_all_matched_button"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Include All in Queue",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = { controller?.addAllMatchingSongsToQueue(shuffle = true) },
                                    modifier = Modifier.weight(1f).height(42.dp).testTag("shuffle_all_matched_button"),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, BentoPrimary),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Shuffle, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Shuffle & Include All",
                                        color = BentoPrimary,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = libraryQuery,
                            onValueChange = { libraryQuery = it },
                            label = { Text("Search matching songs") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BentoPrimary) },
                            trailingIcon = {
                                if (libraryQuery.isNotEmpty()) {
                                    IconButton(onClick = { libraryQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = BentoTextSecondary)
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        visibleMatches.forEach { match ->
                            EnhancedSharedLibraryMatchRow(
                                match = match,
                                canAddTracks = canAddTracks,
                                canPlayNow = canPlayMatchedNow,
                                canRecommend = permissions.allowSharedLibraryRecommendations,
                                isHost = isHost,
                                onPlayNow = { controller?.playSharedLibrarySongNow(match) },
                                onPlayNext = { controller?.playSharedLibrarySongNext(match) },
                                onAddToQueue = { controller?.addSharedLibrarySongToQueue(match) },
                                onRecommend = { controller?.recommendSharedLibrarySong(match) },
                                onOpenPermissions = onPermissions
                            )
                        }

                        if (sharedLibraryMatches.size > visibleMatches.size && libraryQuery.isBlank()) {
                            OutlinedButton(
                                onClick = { showAllMatchesDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, BentoCardBorder)
                            ) {
                                Text(
                                    text = "View All ${sharedLibraryMatches.size} Matching Songs",
                                    color = BentoPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (libraryIndexStatus.isReady && sharedLibraryMatches.isNotEmpty() && visibleMatches.isEmpty()) {
                            Text("No matching songs fit this search.", color = BentoTextSecondary)
                        }
                        if (isHost && !permissions.allowSharedLibraryRecommendations) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Participant recommendations are off (you can still add/play tracks as host).",
                                    color = BentoTextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { controller?.setPermissions(permissions.copy(allowSharedLibraryRecommendations = true)) }
                                ) {
                                    Text("Turn On", color = BentoPrimary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // If participant is on Recommendations tab and Local Song Sharing is OFF, also show the independent Local Song Sharing prompt
                if (!isHost && !permissions.allowLocalSongSharing) {
                    JamFeatureDisabledPromptCard(
                        feature = JamFeature.LOCAL_SONG_SHARING,
                        request = activeFeatureRequests.firstOrNull { it.feature == JamFeature.LOCAL_SONG_SHARING },
                        requestBlocked = featureRequests.firstOrNull { it.feature == JamFeature.LOCAL_SONG_SHARING }?.blockedUids?.contains(currentUid) == true,
                        onRequest = { onRequestFeature(JamFeature.LOCAL_SONG_SHARING) }
                    )
                }

                // Shared Queue Preview Strip (always visible so queued tracks remain accessible)
                SharedQueuePreviewStrip(
                    queue = queue,
                    currentQueueItemId = currentItem?.id,
                    songs = songs,
                    sharedLibraryMatches = sharedLibraryMatches,
                    canSwitchTrack = canSwitchQueueTrack,
                    onViewAllQueue = { selectedRoomTab = 0 },
                    onPlayQueueItem = { itemId ->
                        if (canSwitchQueueTrack) controller?.playQueueItem(itemId)
                    }
                )
            }
        }

        // TAB 2: LOCAL SONG SHARING
        if (selectedRoomTab == 2) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // If participant is on Local Sharing tab and Song Recommendations is OFF, also show the independent Recommendations prompt
                if (!isHost && !permissions.allowSharedLibraryRecommendations) {
                    JamFeatureDisabledPromptCard(
                        feature = JamFeature.SHARED_RECOMMENDATIONS,
                        request = activeFeatureRequests.firstOrNull { it.feature == JamFeature.SHARED_RECOMMENDATIONS },
                        requestBlocked = featureRequests.firstOrNull { it.feature == JamFeature.SHARED_RECOMMENDATIONS }?.blockedUids?.contains(currentUid) == true,
                        onRequest = { onRequestFeature(JamFeature.SHARED_RECOMMENDATIONS) }
                    )
                }

                if (!isHost && !permissions.allowLocalSongSharing) {
                    JamFeatureDisabledPromptCard(
                        feature = JamFeature.LOCAL_SONG_SHARING,
                        request = activeFeatureRequests.firstOrNull { it.feature == JamFeature.LOCAL_SONG_SHARING },
                        requestBlocked = featureRequests.firstOrNull { it.feature == JamFeature.LOCAL_SONG_SHARING }?.blockedUids?.contains(currentUid) == true,
                        onRequest = { onRequestFeature(JamFeature.LOCAL_SONG_SHARING) }
                    )
                } else {
                    // 1) Active Local Song Share Votes & Transfers FIRST so users don't have to scroll down to vote
                    RichRecommendationPanel(
                        title = "Local Song Shares & Votes",
                        recommendations = localShareRecommendations,
                        emptyMessage = "No local song shares or votes yet. Choose a supported audio file below to share it with the room.",
                        participants = participants,
                        currentUid = currentUid,
                        hostUid = hostUid,
                        selectedRecommendationId = selectedRecommendationId,
                        pendingVotes = pendingVotes,
                        songs = songs,
                        sharedLibraryMatches = sharedLibraryMatches,
                        onVote = { recommendationId, vote -> controller?.vote(recommendationId, vote) },
                        onRetryUpload = { controller?.retryUpload(it) },
                        onRetryDownload = { controller?.retryDownload(it) },
                        onViewQueue = { selectedRoomTab = 0 }
                    )

                    // 2) Share a Local Audio File Card
                    JamCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, null, tint = BentoPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("Share a Local Song", color = BentoTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                text = if (permissions.allowLocalSongSharing) "On · up to 25 MB" else "Off",
                                color = if (permissions.allowLocalSongSharing) BentoPrimary else BentoTextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (!permissions.allowLocalSongSharing) {
                            Text("Local song sharing is currently off for this Jam.", color = BentoTextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { controller?.setPermissions(permissions.copy(allowLocalSongSharing = true)) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                                ) {
                                    Text("Turn On Local Song Sharing", fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = onPermissions) { Text("All Permissions", color = BentoPrimary) }
                            }
                        } else {
                            val matchedIdsForLocalShare = remember(sharedLibraryMatches) {
                                sharedLibraryMatches.map { it.song.id }.toSet()
                            }
                            val unmatchedInAppCount = remember(songs, matchedIdsForLocalShare) {
                                songs.count { !it.isMissing && !it.isCorrupted && it.durationMs > 0L && it.id !in matchedIdsForLocalShare }
                            }
                            Text(
                                "Supported audio files (MP3, M4A, AAC, FLAC, OGG, WAV up to 25 MB) transfer directly between devices after unanimous approval. Non-audio files are rejected.",
                                color = BentoTextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )

                            // Two options: Share from In-App Library / Share from Local
                            JamLocalShareSourceSelector(
                                selectedMode = localShareMode,
                                unmatchedCount = unmatchedInAppCount,
                                onSelectInAppLibrary = {
                                    localShareMode = JamLocalShareMode.IN_APP_LIBRARY
                                    localFileError = null
                                },
                                onSelectLocalStorage = {
                                    localShareMode = JamLocalShareMode.LOCAL_STORAGE
                                    localFileError = null
                                }
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = localShareConsent, onCheckedChange = { localShareConsent = it })
                                Text("I have the right to share the selected audio.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
                            }

                            // Selected In-App Library Song Preview + Start 30-second vote
                            selectedInAppShare?.let { inApp ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BentoCardPurple.copy(alpha = 0.5f)).padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    com.example.ui.components.AlbumArtView(
                                        song = inApp.song,
                                        modifier = Modifier.size(54.dp),
                                        cornerRadius = 12
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(inApp.track.title, color = BentoTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${inApp.track.artist} · ${formatJamDuration(inApp.track.durationMs)} · ${formatJamSize(inApp.sizeBytes)}", color = BentoTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { selectedInAppShare = null }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear selection", tint = BentoTextSecondary)
                                    }
                                }
                                JamButton("Start 30-second vote", Icons.Default.Groups, enabled = localShareConsent && state !is JamUiState.Loading) {
                                    controller?.recommendLocalTrack(inApp.track, inApp.uri, inApp.mimeType)
                                    selectedInAppShare = null
                                }
                            }

                            // Selected Local Storage Audio Preview + Start 30-second vote
                            selectedLocalAudio?.let { audio ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BentoCardPurple.copy(alpha = 0.5f)).padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (audio.artwork != null) {
                                        androidx.compose.foundation.Image(audio.artwork, null, modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)))
                                    } else {
                                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(BentoCardSurface), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.MusicNote, null, tint = BentoPrimary)
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(audio.track.title, color = BentoTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${audio.track.artist} · ${formatJamDuration(audio.track.durationMs)} · ${formatJamSize(audio.sizeBytes)}", color = BentoTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { selectedLocalAudio = null }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear selection", tint = BentoTextSecondary)
                                    }
                                }
                                JamButton("Start 30-second vote", Icons.Default.Groups, enabled = localShareConsent && state !is JamUiState.Loading) {
                                    controller?.recommendLocalTrack(audio.track, audio.uri, audio.mimeType)
                                    selectedLocalAudio = null
                                }
                            }

                            localFileError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                            if (localShareMode == JamLocalShareMode.IN_APP_LIBRARY) {
                                JamEmbeddedInAppLibraryBrowser(
                                    allSongs = songs,
                                    sharedLibraryMatches = sharedLibraryMatches,
                                    queue = queue,
                                    selectedSongId = selectedInAppShare?.song?.id,
                                    onSelectSongToShare = { song ->
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) { prepareInAppSongForLocalShare(context, song) }
                                                .onSuccess { prepared ->
                                                    if (queue.any { it.track.sharedTrackId == prepared.track.sharedTrackId }) {
                                                        localFileError = "This song is already in the Jam shared queue."
                                                    } else {
                                                        selectedInAppShare = prepared
                                                        selectedLocalAudio = null
                                                        localFileError = null
                                                    }
                                                }
                                                .onFailure { localFileError = it.message ?: "This song couldn't be prepared for sharing." }
                                        }
                                    }
                                )
                            } else {
                                JamButton("Choose Audio File (MP3 / Audio)", Icons.Default.Add, enabled = localShareConsent && state !is JamUiState.Loading) {
                                    localAudioPicker.launch(arrayOf("audio/*"))
                                }
                            }
                        }
                    }
                }

                // Shared Queue Preview Strip
                SharedQueuePreviewStrip(
                    queue = queue,
                    currentQueueItemId = currentItem?.id,
                    songs = songs,
                    sharedLibraryMatches = sharedLibraryMatches,
                    canSwitchTrack = canSwitchQueueTrack,
                    onViewAllQueue = { selectedRoomTab = 0 },
                    onPlayQueueItem = { itemId ->
                        if (canSwitchQueueTrack) controller?.playQueueItem(itemId)
                    }
                )
            }
        }
        item { JamStatus(state) }
    }
}

@Composable
private fun PermissionsPage(
    permissions: JamPermissions,
    participants: List<JamParticipant>,
    isHost: Boolean,
    onPermissionsChange: (JamPermissions) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    state: JamUiState
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JamHeader("Jam permissions", "Choose who can control this session", onBack)
        if (!isHost) Text("Only the host can edit these permissions.", color = BentoTextSecondary)
        JamPermissionsBentoGrid(
            permissions = permissions,
            isHost = isHost,
            onToggleRecommendations = if (isHost) { enabled ->
                onPermissionsChange(permissions.copy(allowSharedLibraryRecommendations = enabled))
            } else null,
            onToggleLocalSharing = if (isHost) { enabled ->
                onPermissionsChange(permissions.copy(allowLocalSongSharing = enabled))
            } else null
        )
        JamCard {
            PermissionRows(
                permissions = permissions,
                onChange = onPermissionsChange,
                allowSelected = true,
                enabled = isHost,
                participants = participants
            )
            JamFeatureToggles(
                permissions,
                onPermissionsChange,
                enabled = isHost
            )
        }
        JamButton("Save permissions", Icons.Default.Check, enabled = isHost && state !is JamUiState.Loading, onClick = onSave)
        JamStatus(state)
    }
}

@Composable
private fun JamFeatureToggles(
    permissions: JamPermissions,
    onChange: (JamPermissions) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Song sharing", style = MaterialTheme.typography.titleMedium, color = BentoTextPrimary, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow local-song sharing", color = BentoTextPrimary, fontWeight = FontWeight.Medium)
                Text("Participants can propose an audio file for unanimous approval.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = permissions.allowLocalSongSharing,
                onCheckedChange = { onChange(permissions.copy(allowLocalSongSharing = it)) },
                enabled = enabled
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow shared-library recommendations", color = BentoTextPrimary, fontWeight = FontWeight.Medium)
                Text("Participants vote on songs already available on every device.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = permissions.allowSharedLibraryRecommendations,
                onCheckedChange = { onChange(permissions.copy(allowSharedLibraryRecommendations = it)) },
                enabled = enabled
            )
        }
    }
}

@Composable
private fun PermissionRows(
    permissions: JamPermissions,
    onChange: (JamPermissions) -> Unit,
    allowSelected: Boolean,
    enabled: Boolean = true,
    participants: List<JamParticipant> = emptyList()
) {
    val rules = listOf(
        JamPermissionKind.PLAY_PAUSE to "Play and pause",
        JamPermissionKind.SEEK to "Seek",
        JamPermissionKind.SKIP_TRACK to "Previous and next",
        JamPermissionKind.ADD_TO_QUEUE to "Add tracks",
        JamPermissionKind.MANAGE_QUEUE to "Remove or reorder"
    )
    rules.forEach { (kind, label) ->
        val current = ruleFor(permissions, kind)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = BentoTextPrimary, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val modes = listOf(JamAccessMode.HOST_ONLY, JamAccessMode.EVERYONE)
                modes.forEach { mode ->
                    FilterChip(
                        selected = current.mode == mode,
                        onClick = { onChange(updateRule(permissions, kind, current.copy(mode = mode, selectedUids = emptySet()))) },
                        label = { Text(if (mode == JamAccessMode.HOST_ONLY) "Host" else "Everyone") },
                        enabled = enabled
                    )
                }
                if (allowSelected) {
                    FilterChip(
                        selected = current.mode == JamAccessMode.SELECTED,
                        onClick = { onChange(updateRule(permissions, kind, current.copy(mode = JamAccessMode.SELECTED))) },
                        label = { Text("Selected") },
                        enabled = enabled
                    )
                }
            }
            if (allowSelected && current.mode == JamAccessMode.SELECTED) {
                participants.filterNot { it.isHost }.forEach { participant ->
                    val selected = participant.uid in current.selectedUids
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled) {
                            val selectedUids = current.selectedUids.toMutableSet().apply {
                                if (selected) remove(participant.uid) else add(participant.uid)
                            }
                            onChange(updateRule(permissions, kind, current.copy(selectedUids = selectedUids)))
                        }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (selected) Icons.Default.Check else Icons.Default.PersonAdd, null, tint = if (selected) BentoPrimary else BentoTextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(participant.displayName, color = BentoTextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun JamHeader(title: String, subtitle: String, onBack: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BentoTextPrimary) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BentoTextSecondary)
        }
        trailing?.invoke()
    }
}

@Composable
private fun JamCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val glass = LocalGlassThemeConfig.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = BentoCardSurface),
        border = BorderStroke(1.dp, BentoCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = if (glass.glassEnabled) 0.dp else 2.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun JamAccordion(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    JamCard {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle).padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = BentoTextPrimary, fontWeight = FontWeight.Bold)
                Text(summary, color = BentoTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide $title" else "Show $title",
                tint = BentoTextSecondary
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun JamButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun JamStatus(state: JamUiState) {
    when (state) {
        JamUiState.Idle -> Unit
        JamUiState.Loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = BentoPrimary) }
        is JamUiState.Error -> JamCard {
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        is JamUiState.Notice -> JamCard {
            Text(state.message, color = BentoPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ParticipantRow(participant: JamParticipant) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!participant.photoUrl.isNullOrBlank()) {
            AsyncImage(participant.photoUrl, contentDescription = null, modifier = Modifier.size(38.dp).clip(CircleShape))
        } else {
            Box(Modifier.size(38.dp).clip(CircleShape).background(BentoCardPurple), contentAlignment = Alignment.Center) {
                Text(participant.displayName.take(1).uppercase(), color = BentoPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(participant.displayName, color = BentoTextPrimary, fontWeight = FontWeight.Medium)
            Text(if (participant.isHost) "Host" else if (participant.isConnected) "Connected" else "Reconnecting…", color = BentoTextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        if (participant.isHost) Text("HOST", color = BentoPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CompactParticipantRow(participant: JamParticipant, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        if (!participant.photoUrl.isNullOrBlank()) {
            AsyncImage(participant.photoUrl, contentDescription = null, modifier = Modifier.size(30.dp).clip(CircleShape))
        } else {
            Box(Modifier.size(30.dp).clip(CircleShape).background(BentoCardPurple), contentAlignment = Alignment.Center) {
                Text(participant.displayName.take(1).uppercase(), color = BentoPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(participant.displayName, color = BentoTextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (participant.isHost) "Host" else if (participant.isConnected) "Connected" else "Reconnecting…", color = BentoTextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        if (participant.isHost) Text("HOST", color = BentoPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        trailing?.invoke()
    }
}



@Composable
private fun LocalSongRow(song: Song, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onAdd).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (song.artworkUri != null) AsyncImage(song.artworkUri, contentDescription = null, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
        else Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(BentoCardPurple), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = BentoPrimary) }
        Column(Modifier.weight(1f)) {
            Text(song.displayTitle, color = BentoTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(song.displayArtist, color = BentoTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add to shared queue", tint = BentoPrimary) }
    }
}

@Composable
private fun JamQrCode(url: String) {
    val image = remember(url) {
        runCatching {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320)
            android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until 320) for (y in 0 until 320) {
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }.asImageBitmap()
        }.getOrNull()
    }
    if (image != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(image, contentDescription = "Jam invitation QR code", modifier = Modifier.size(220.dp))
        }
    }
}

private data class LocalAudioSelection(
    val uri: Uri,
    val track: com.example.jam.JamTrack,
    val mimeType: String,
    val sizeBytes: Long,
    val artwork: androidx.compose.ui.graphics.ImageBitmap?
)

private fun readLocalAudioMetadata(resolver: ContentResolver, uri: Uri, cacheDir: java.io.File): LocalAudioSelection {
    val details = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to (if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L)
            } else null
        }
    }.getOrNull()
    val displayName = details?.first.orEmpty()

    val stagingDir = java.io.File(cacheDir, "jam_staged_uploads").apply { mkdirs() }
    val cutoffMs = System.currentTimeMillis() - 30 * 60_000L
    stagingDir.listFiles()?.forEach { f ->
        if (f.isFile && f.lastModified() < cutoffMs) runCatching { f.delete() }
    }
    val stagedFile = java.io.File(stagingDir, "jam_doc_${System.currentTimeMillis()}.part")
    var size = 0L
    try {
        resolver.openInputStream(uri)?.use { stream ->
            stagedFile.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_JAM_AUDIO_BYTES) {
                        throw IllegalArgumentException("Audio files must be 25 MB or smaller.")
                    }
                    output.write(buffer, 0, count)
                }
            }
        } ?: throw IllegalArgumentException("This file couldn't be opened.")
    } catch (e: Throwable) {
        stagedFile.delete()
        if (e is IllegalArgumentException) throw e
        throw IllegalArgumentException("This file couldn't be opened.")
    }

    if (size !in 4..MAX_JAM_AUDIO_BYTES || !stagedFile.isFile) {
        stagedFile.delete()
        throw IllegalArgumentException("Audio files must be between 4 bytes and 25 MB.")
    }
    val extensionMime = when (displayName.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }
    val detectedMimeType = runCatching { resolver.getType(uri)?.lowercase()?.takeIf(String::isNotBlank) }.getOrNull()
    if (detectedMimeType != null && detectedMimeType !in ALLOWED_JAM_AUDIO_MIMES) {
        stagedFile.delete()
        throw IllegalArgumentException("Only audio files are supported. Choose an MP3, M4A, AAC, FLAC, OGG, or WAV file.")
    }
    val mimeType = detectedMimeType ?: extensionMime ?: "audio/mpeg"
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(stagedFile.absolutePath)
        val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
        val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val fallbackTitle = com.example.util.TagCleaner.cleanTitle(
            displayName.substringBeforeLast('.', displayName),
            rawArtist
        ).ifBlank { displayName.substringBeforeLast('.', displayName).trim() }
        val title = rawTitle.ifBlank { fallbackTitle }.ifBlank { "Shared Audio Track" }
        val artist = rawArtist.ifBlank { "Unknown Artist" }
        if (duration <= 0L) {
            stagedFile.delete()
            throw IllegalArgumentException("This audio file needs a valid readable duration.")
        }
        val artwork = runCatching {
            retriever.embeddedPicture?.let { bytes ->
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > 256 || bounds.outHeight / sampleSize > 256) {
                    sampleSize *= 2
                }
                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)?.asImageBitmap()
            }
        }.getOrNull()
        val candidate = com.example.jam.JamTrack("", title.take(180), artist.take(180), album.take(180), duration)
        return LocalAudioSelection(
            uri = Uri.fromFile(stagedFile),
            track = candidate.copy(sharedTrackId = com.example.jam.sharedTrackId(candidate)),
            mimeType = mimeType,
            sizeBytes = size,
            artwork = artwork
        )
    } catch (error: IllegalArgumentException) {
        stagedFile.delete()
        throw error
    } catch (_: Throwable) {
        stagedFile.delete()
        throw IllegalArgumentException("This audio file is unreadable or corrupted.")
    } finally {
        runCatching { retriever.release() }
    }
}

private fun formatJamDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatJamSize(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

private const val MAX_JAM_AUDIO_BYTES = 25L * 1024L * 1024L
private val ALLOWED_JAM_AUDIO_MIMES = setOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac", "audio/ogg", "audio/wav", "audio/x-wav")

private fun permissionForPreset(preset: String): JamPermissions = when (preset) {
    "Host Controlled" -> JamPermissions(
        playPause = JamPermissionRule(), seek = JamPermissionRule(), skipTrack = JamPermissionRule(),
        addToQueue = JamPermissionRule(), manageQueue = JamPermissionRule()
    )
    "Open Listening" -> JamPermissions(
        playPause = JamPermissionRule(JamAccessMode.EVERYONE), seek = JamPermissionRule(JamAccessMode.EVERYONE),
        skipTrack = JamPermissionRule(JamAccessMode.EVERYONE), addToQueue = JamPermissionRule(JamAccessMode.EVERYONE),
        manageQueue = JamPermissionRule(JamAccessMode.EVERYONE)
    )
    "Collaborative" -> JamPermissions()
    else -> JamPermissions(
        playPause = JamPermissionRule(), seek = JamPermissionRule(), skipTrack = JamPermissionRule(),
        addToQueue = JamPermissionRule(), manageQueue = JamPermissionRule()
    )
}

private fun ruleFor(permissions: JamPermissions, kind: JamPermissionKind) = when (kind) {
    JamPermissionKind.PLAY_PAUSE -> permissions.playPause
    JamPermissionKind.SEEK -> permissions.seek
    JamPermissionKind.SKIP_TRACK -> permissions.skipTrack
    JamPermissionKind.ADD_TO_QUEUE -> permissions.addToQueue
    JamPermissionKind.MANAGE_QUEUE -> permissions.manageQueue
}

private fun updateRule(permissions: JamPermissions, kind: JamPermissionKind, rule: JamPermissionRule) = when (kind) {
    JamPermissionKind.PLAY_PAUSE -> permissions.copy(playPause = rule)
    JamPermissionKind.SEEK -> permissions.copy(seek = rule)
    JamPermissionKind.SKIP_TRACK -> permissions.copy(skipTrack = rule)
    JamPermissionKind.ADD_TO_QUEUE -> permissions.copy(addToQueue = rule)
    JamPermissionKind.MANAGE_QUEUE -> permissions.copy(manageQueue = rule)
}

private object JamRepositoryLimit { const val PARTICIPANTS = 10 }
