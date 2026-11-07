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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Reorder
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
import coil.compose.AsyncImage
import com.example.data.model.Song
import com.example.jam.JamAccessMode
import com.example.jam.JamFeatureController
import com.example.jam.JamPage
import com.example.jam.JamParticipant
import com.example.jam.JamPermissionKind
import com.example.jam.JamPermissionRule
import com.example.jam.JamPermissions
import com.example.jam.JamRecommendation
import com.example.jam.JamRecommendationType
import com.example.jam.JamVote
import com.example.jam.JamQueueItem
import com.example.jam.JamSession
import com.example.jam.JamUiState
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
    val selectedRecommendationId = controller?.selectedRecommendationId?.collectAsStateWithLifecycle()?.value
    val invite = controller?.invite?.collectAsStateWithLifecycle()?.value
    val localTrackAvailable = controller?.localTrackAvailable?.collectAsStateWithLifecycle()?.value ?: false
    val currentUid = controller?.currentUid?.collectAsStateWithLifecycle()?.value
    val clipboard = LocalClipboardManager.current
    val glass = LocalGlassThemeConfig.current
    var permissionDraft by remember(session?.id, session?.revision) { mutableStateOf(session?.permissions ?: JamPermissions()) }
    var selectedPreset by rememberSaveable { mutableStateOf("Collaborative") }
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

    BackHandler(enabled = page !in setOf(JamPage.LANDING, JamPage.ACTIVE) || (page == JamPage.ACTIVE && session == null)) {
        when (page) {
            JamPage.CREATE, JamPage.JOIN, JamPage.INVITE -> controller?.openPage(if (session != null) JamPage.ACTIVE else JamPage.LANDING)
            JamPage.PERMISSIONS -> controller?.openPage(JamPage.ACTIVE)
            else -> controller?.openPage(JamPage.LANDING)
        }
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
                onPermissionsChange = { permissionDraft = it },
                onBack = { controller?.openPage(JamPage.LANDING) },
                onCreate = { controller?.createJam(permissionDraft) },
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
                onBackToJam = { controller?.dismissInvite() },
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
                selectedRecommendationId = selectedRecommendationId,
                songs = songs,
                localTrackAvailable = localTrackAvailable,
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
                onLeave = { controller?.leaveJam() },
                onClose = { controller?.closeJam() }
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
        JamCard {
            Icon(Icons.Default.Lock, contentDescription = null, tint = BentoPrimary)
            Text("Sign in required", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
            Text("Jam uses your existing OffGrid account. Guest music scanning, local playlists, and regular playback remain available.", color = BentoTextSecondary)
        }
        JamStatus(state)
    }
}

@Composable
private fun CreatePage(
    permissions: JamPermissions,
    selectedPreset: String,
    onPresetChange: (String) -> Unit,
    onPermissionsChange: (JamPermissions) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    state: JamUiState
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JamHeader("Create a Jam", "Choose who can control the session", onBack)
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
                enabled = true,
                showSharedRecommendations = permissions.manageQueue.mode == JamAccessMode.HOST_ONLY
            )
        }
        JamButton("Create Jam", Icons.Default.Groups, enabled = state !is JamUiState.Loading, onClick = onCreate)
        JamStatus(state)
    }
}

@Composable
private fun JoinPage(onBack: () -> Unit, onJoin: (String) -> Unit, state: JamUiState) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        JamHeader("Join a Jam", "Enter the invitation code", onBack)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isLetterOrDigit).take(16).uppercase() },
            label = { Text("Invitation code") },
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = BentoPrimary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth()
        )
        JamButton("Continue", Icons.Default.PersonAdd, enabled = code.length >= 12 && state !is JamUiState.Loading) { onJoin(code) }
        Text("Opening a valid invitation link or scanning its QR code brings you to this same join flow.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
        JamStatus(state)
    }
}

@Composable
private fun InvitePage(
    invite: com.example.jam.JamInvite?,
    onBackToJam: () -> Unit,
    onCopyCode: (String) -> Unit,
    onShare: (String?, String) -> Unit,
    state: JamUiState
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        JamHeader("Invite people", "Share this code or link with your group")
        if (state is JamUiState.Loading || invite == null) {
            CircularProgressIndicator(color = BentoPrimary)
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
            JamButton("Open Jam", Icons.Default.Groups, onClick = onBackToJam)
        }
        JamStatus(state)
    }
}

@Composable
private fun ActivePage(
    controller: JamFeatureController?,
    session: JamSession?,
    participants: List<JamParticipant>,
    queue: List<JamQueueItem>,
    recommendations: List<JamRecommendation>,
    selectedRecommendationId: String?,
    songs: List<Song>,
    localTrackAvailable: Boolean,
    currentUid: String,
    notificationsDenied: Boolean,
    onNotificationSettings: () -> Unit,
    state: JamUiState,
    onPermissions: () -> Unit,
    onLeave: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var localShareConsent by rememberSaveable { mutableStateOf(false) }
    var localFileError by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLocalAudio by remember { mutableStateOf<LocalAudioSelection?>(null) }
    val localAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            coroutineScope.launch {
                runCatching { withContext(Dispatchers.IO) { readLocalAudioMetadata(context.contentResolver, uri) } }
                    .onSuccess { selected ->
                        if (queue.any { it.track.sharedTrackId == selected.track.sharedTrackId }) {
                            localFileError = "This song is already in the Jam shared library."
                        } else {
                            selectedLocalAudio = selected
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
        ?: queue.firstOrNull { it.track.sharedTrackId == session?.playback?.sharedTrackId }
    val isHost = session?.hostUid != null && session.hostUid == currentUid
    val permissionUid = currentUid
    val permissions = session?.permissions ?: JamPermissions()
    val durationMs = currentItem?.track?.durationMs?.coerceAtLeast(1L) ?: 1L
    val visibleSongs = remember(songs, libraryQuery) {
        val query = libraryQuery.trim()
        if (query.isBlank()) songs.take(8) else songs.asSequence()
            .filter { it.displayTitle.contains(query, true) || it.displayArtist.contains(query, true) || it.displayAlbum.contains(query, true) }
            .take(12).toList()
    }
    LaunchedEffect(session?.playback, isSeeking) {
        while (!isSeeking) {
            val playback = session?.playback ?: break
            sliderPosition = playback.estimatedPosition().toFloat().coerceIn(0f, durationMs.toFloat())
            kotlinx.coroutines.delay(500L)
        }
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
            JamHeader(
                title = "OffGrid Jam",
                subtitle = if (session == null) "Connecting to your session…" else "${session.participantCount}/${JamRepositoryLimit.PARTICIPANTS} listeners · ${if (session.isActive) "Live" else "Ended"}",
                onBack = { controller?.openPage(JamPage.LANDING) },
                trailing = {
                    if (isHost) IconButton(onClick = onPermissions) { Icon(Icons.Default.Tune, "Permissions", tint = BentoPrimary) }
                    IconButton(onClick = if (isHost) onClose else onLeave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Leave Jam", tint = BentoTextSecondary) }
                }
            )
        }
        item {
            RecommendationPanel(
                recommendations = recommendations,
                participants = participants,
                currentUid = currentUid,
                selectedRecommendationId = selectedRecommendationId,
                onVote = { recommendationId, vote -> controller?.vote(recommendationId, vote) },
                onRetryUpload = { controller?.retryUpload(it) },
                onRetryDownload = { controller?.retryDownload(it) }
            )
        }
        item {
            JamCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val localArtwork = currentItem?.track?.let { track ->
                        songs.firstOrNull { it.displayTitle.equals(track.title, true) && it.displayArtist.equals(track.artist, true) }?.artworkUri
                    }
                    if (!localArtwork.isNullOrBlank()) {
                        AsyncImage(model = localArtwork, contentDescription = null, modifier = Modifier.size(70.dp).clip(RoundedCornerShape(14.dp)))
                    } else {
                        Box(Modifier.size(70.dp).clip(RoundedCornerShape(14.dp)).background(BentoCardPurple), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, tint = BentoPrimary, modifier = Modifier.size(32.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(currentItem?.track?.title ?: "Nothing queued", color = BentoTextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(currentItem?.track?.artist ?: "Add a track from your library", color = BentoTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (currentItem != null && !localTrackAvailable) {
                            Text("This track isn't available on your device", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Slider(
                    value = sliderPosition.coerceIn(0f, durationMs.toFloat()),
                    onValueChange = { isSeeking = true; sliderPosition = it },
                    onValueChangeFinished = { controller?.seek(sliderPosition.toLong()); isSeeking = false },
                    valueRange = 0f..durationMs.toFloat(),
                    enabled = currentItem != null && localTrackAvailable && session?.permissions?.seek?.allows(permissionUid, session.hostUid) == true
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { controller?.skip(-1) }, enabled = session?.permissions?.skipTrack?.allows(permissionUid, session.hostUid) == true) { Icon(Icons.Default.SkipPrevious, "Previous", tint = BentoTextPrimary) }
                    Surface(
                        modifier = Modifier.size(56.dp).clip(CircleShape).clickable(enabled = currentItem != null && localTrackAvailable) { controller?.togglePlayback() },
                        color = BentoPrimary,
                        shape = CircleShape
                    ) { Box(contentAlignment = Alignment.Center) { Icon(if (session?.playback?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = BentoOnPrimary, modifier = Modifier.size(30.dp)) } }
                    IconButton(onClick = { controller?.skip(1) }, enabled = session?.permissions?.skipTrack?.allows(permissionUid, session.hostUid) == true) { Icon(Icons.Default.SkipNext, "Next", tint = BentoTextPrimary) }
                }
            }
        }
        item {
            JamCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Groups, null, tint = BentoPrimary)
                    Text("Listeners", color = BentoTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${participants.size} connected", color = BentoTextSecondary, style = MaterialTheme.typography.labelMedium)
                }
                participants.forEach { ParticipantRow(it) }
            }
        }
        item {
            JamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QueueMusic, null, tint = BentoPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Shared queue", color = BentoTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${queue.size}", color = BentoTextSecondary)
                }
                if (queue.isEmpty()) Text("Add a track from your library to get started.", color = BentoTextSecondary)
            }
        }
        itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
            QueueRow(
                item = item,
                isCurrent = item.id == session?.playback?.queueItemId,
                canManage = session?.permissions?.manageQueue?.allows(permissionUid, session.hostUid) == true,
                canRecommend = permissions.allowSharedLibraryRecommendations &&
                    permissions.manageQueue.mode == JamAccessMode.HOST_ONLY && !isHost,
                onRecommend = { controller?.recommendSharedTrack(item.track) },
                onRemove = { controller?.removeQueueItem(item.id) },
                onMoveUp = { controller?.moveQueueItem(item.id, -1) },
                onMoveDown = { controller?.moveQueueItem(item.id, 1) },
                isFirst = index == 0,
                isLast = index == queue.lastIndex
            )
        }
        item {
            if (permissions.allowLocalSongSharing) {
                JamCard {
                    Text("Share a local song", color = BentoTextPrimary, fontWeight = FontWeight.Bold)
                    Text("Everyone must approve it before upload. The Jam keeps playing while each device downloads and verifies the file.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = localShareConsent, onCheckedChange = { localShareConsent = it })
                        Text("I have the right to share this audio file.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    selectedLocalAudio?.let { audio ->
                        JamCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (audio.artwork != null) {
                                    androidx.compose.foundation.Image(audio.artwork, null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                                } else {
                                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(BentoCardPurple), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.MusicNote, null, tint = BentoPrimary)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(audio.track.title, color = BentoTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(audio.track.artist, color = BentoTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${formatJamDuration(audio.track.durationMs)} · ${formatJamSize(audio.sizeBytes)} · ${participants.firstOrNull { it.uid == currentUid }?.displayName ?: "You"}", color = BentoTextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            JamButton("Start 30-second vote", Icons.Default.Groups, enabled = localShareConsent && state !is JamUiState.Loading) {
                                controller?.recommendLocalTrack(audio.track, audio.uri, audio.mimeType)
                                selectedLocalAudio = null
                            }
                        }
                    }
                    JamButton("Choose audio file", Icons.Default.Add, enabled = localShareConsent && state !is JamUiState.Loading) {
                        localAudioPicker.launch(arrayOf("audio/*"))
                    }
                    localFileError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            JamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = BentoPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Add from your library", color = BentoTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = libraryQuery, onValueChange = { libraryQuery = it }, label = { Text("Search local tracks") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (songs.isEmpty()) Text("Scan your local library before adding tracks.", color = BentoTextSecondary)
                else if (!searchMode && libraryQuery.isBlank()) {
                    visibleSongs.take(4).forEach { song -> LocalSongRow(song) { controller?.addSong(song) } }
                    TextButton(onClick = { searchMode = true }) { Text("Show matches", color = BentoPrimary) }
                } else {
                    visibleSongs.forEach { song -> LocalSongRow(song) { controller?.addSong(song) } }
                    if (visibleSongs.isEmpty()) Text("No local tracks match this search.", color = BentoTextSecondary)
                }
            }
        }
        item { JamStatus(state) }
    }
}

@Composable
private fun RecommendationPanel(
    recommendations: List<JamRecommendation>,
    participants: List<JamParticipant>,
    currentUid: String,
    selectedRecommendationId: String?,
    onVote: (String, JamVote) -> Unit,
    onRetryUpload: (String) -> Unit,
    onRetryDownload: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        JamCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null, tint = BentoPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Song recommendations", color = BentoTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(recommendations.count { it.isVoting }.toString(), color = BentoTextSecondary)
            }
            if (recommendations.isEmpty()) {
                Text("Shared-library votes and local-song transfers will appear here.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        recommendations.take(8).forEach { recommendation ->
            RecommendationCard(
                recommendation = recommendation,
                participants = participants,
                currentUid = currentUid,
                selected = selectedRecommendationId == recommendation.id,
                onVote = { onVote(recommendation.id, it) },
                onRetryUpload = { onRetryUpload(recommendation.id) },
                onRetryDownload = { onRetryDownload(recommendation.id) }
            )
        }
    }
}

@Composable
private fun RecommendationCard(
    recommendation: JamRecommendation,
    participants: List<JamParticipant>,
    currentUid: String,
    selected: Boolean,
    onVote: (JamVote) -> Unit,
    onRetryUpload: () -> Unit,
    onRetryDownload: () -> Unit
) {
    var nowMs by remember(recommendation.id, recommendation.status) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recommendation.id, recommendation.status) {
        while (recommendation.status == "VOTING") {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val required = recommendation.requiredApprovalCount
    val resultText = when (recommendation.status) {
        "VOTING" -> "${((recommendation.deadlineAtMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L}s left · ${recommendation.approvalCount}/$required approvals"
        "APPROVED_PENDING_UPLOAD" -> "Approved unanimously · waiting for upload"
        "UPLOADING" -> "Uploading · ${recommendation.uploadProgress}%"
        "DOWNLOADING" -> "Downloading on each device"
        "READY" -> "Ready for the shared queue"
        "REJECTED" -> "Rejected · queue unchanged"
        "EXPIRED" -> "Expired · queue unchanged"
        "FAILED" -> recommendation.failureReason ?: "Transfer failed"
        else -> recommendation.status.lowercase().replace('_', ' ')
    }
    JamCard(modifier = if (selected) Modifier.border(1.5.dp, BentoPrimary, RoundedCornerShape(22.dp)) else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = BentoPrimary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(recommendation.track.title, color = BentoTextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${recommendation.track.artist} · ${if (recommendation.type == JamRecommendationType.LOCAL_SONG) "Local song" else "Shared library"}", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Text(resultText, color = if (recommendation.status in setOf("REJECTED", "EXPIRED", "FAILED")) MaterialTheme.colorScheme.error else BentoPrimary, style = MaterialTheme.typography.labelSmall)
        }
        Text("Recommended by ${recommendation.proposerName}", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
        Text("${recommendation.approvalCount} accepted · ${recommendation.rejectionCount} rejected · ${recommendation.pendingCount} pending · ${recommendation.requiredApprovalCount} required", color = BentoTextSecondary, style = MaterialTheme.typography.labelMedium)
        recommendation.eligibleUids.forEach { uid ->
            val name = participants.firstOrNull { it.uid == uid }?.displayName ?: if (uid == currentUid) "You" else "Listener"
            val vote = recommendation.votes[uid] ?: JamVote.PENDING
            Text("$name — ${vote.name.lowercase().replaceFirstChar(Char::uppercase)}", color = when (vote) {
                JamVote.ACCEPT -> BentoPrimary
                JamVote.REJECT -> MaterialTheme.colorScheme.error
                JamVote.PENDING -> BentoTextSecondary
            }, style = MaterialTheme.typography.bodySmall)
        }
        if (recommendation.isVoting && recommendation.votes[currentUid] == JamVote.PENDING && nowMs < recommendation.deadlineAtMs) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onVote(JamVote.ACCEPT) }) { Text("Accept", color = BentoPrimary) }
                TextButton(onClick = { onVote(JamVote.REJECT) }) { Text("Reject", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (recommendation.type == JamRecommendationType.LOCAL_SONG && recommendation.status in setOf("APPROVED_PENDING_UPLOAD", "UPLOADING", "DOWNLOADING", "READY", "FAILED")) {
            if (recommendation.uploadStatus == "UPLOADING") {
                Text("Upload ${recommendation.uploadProgress}%", color = BentoTextSecondary, style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.LinearProgressIndicator(recommendation.uploadProgress / 100f, modifier = Modifier.fillMaxWidth())
            }
            recommendation.eligibleUids.forEach { uid ->
                val name = participants.firstOrNull { it.uid == uid }?.displayName ?: if (uid == currentUid) "You" else "Listener"
                val status = recommendation.downloads[uid] ?: JamDownloadState()
                val statusLabel = when {
                    recommendation.status == "UPLOADING" && uid == recommendation.proposerUid -> "Uploading · ${recommendation.uploadProgress}%"
                    recommendation.status in setOf("APPROVED_PENDING_UPLOAD", "UPLOADING") -> "Waiting for upload"
                    else -> when (status.status) {
                    "WAITING_FOR_UPLOAD" -> "Waiting for upload"
                    "UPLOADING" -> "Uploading"
                    "WAITING_TO_DOWNLOAD" -> "Waiting to download"
                    "DOWNLOADING" -> "Downloading · ${status.progress}%"
                    "VERIFYING_FILE" -> "Verifying file"
                    "READY_FOR_PLAYBACK" -> "Download completed · Ready"
                    "DOWNLOAD_FAILED" -> "Download failed · Retry required"
                    else -> status.status.lowercase().replace('_', ' ')
                    }
                }
                Text("$name — $statusLabel", color = if (status.status.endsWith("FAILED")) MaterialTheme.colorScheme.error else BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
                if (status.status == "DOWNLOADING") {
                    androidx.compose.material3.LinearProgressIndicator(status.progress / 100f, modifier = Modifier.fillMaxWidth())
                }
                if (uid == currentUid && status.status == "DOWNLOAD_FAILED") {
                    TextButton(onClick = onRetryDownload) { Text("Retry download", color = BentoPrimary) }
                }
            }
            if (currentUid == recommendation.proposerUid && recommendation.uploadStatus == "UPLOAD_FAILED") {
                TextButton(onClick = onRetryUpload) { Text("Retry upload", color = BentoPrimary) }
            }
        }
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
                enabled = isHost,
                showSharedRecommendations = permissions.manageQueue.mode == JamAccessMode.HOST_ONLY
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
    enabled: Boolean,
    showSharedRecommendations: Boolean
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
        if (showSharedRecommendations) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow shared-library recommendations", color = BentoTextPrimary, fontWeight = FontWeight.Medium)
                    Text("Participants vote on songs already in the Jam library.", color = BentoTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = permissions.allowSharedLibraryRecommendations,
                    onCheckedChange = { onChange(permissions.copy(allowSharedLibraryRecommendations = it)) },
                    enabled = enabled
                )
            }
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
private fun QueueRow(
    item: JamQueueItem,
    isCurrent: Boolean,
    canManage: Boolean,
    canRecommend: Boolean,
    onRecommend: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    isFirst: Boolean,
    isLast: Boolean
) {
    JamCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.MusicNote, null, tint = if (isCurrent) BentoPrimary else BentoTextSecondary)
            Column(Modifier.weight(1f)) {
                Text(item.track.title, color = BentoTextPrimary, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.track.artist, color = BentoTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (canRecommend) TextButton(onClick = onRecommend) { Text("Recommend", color = BentoPrimary) }
            if (canManage) {
                IconButton(onClick = onMoveUp, enabled = !isFirst) { Icon(Icons.Default.Reorder, "Move up", tint = BentoTextSecondary) }
                IconButton(onClick = onMoveDown, enabled = !isLast) { Icon(Icons.Default.Reorder, "Move down", tint = BentoTextSecondary) }
                IconButton(onClick = onRemove) { Icon(Icons.Default.RemoveCircleOutline, "Remove", tint = BentoTextSecondary) }
            }
        }
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

private fun readLocalAudioMetadata(resolver: ContentResolver, uri: Uri): LocalAudioSelection {
    val details = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to (if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L)
        } else null
    }
    val displayName = details?.first.orEmpty()
    var size = details?.second ?: -1L
    if (size <= 0L) {
        size = 0L
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                size += count
                if (size > MAX_JAM_AUDIO_BYTES) throw IllegalArgumentException("Audio files must be 25 MB or smaller.")
            }
        } ?: throw IllegalArgumentException("This file couldn't be opened.")
    }
    if (size !in 4..MAX_JAM_AUDIO_BYTES) throw IllegalArgumentException("Audio files must be between 4 bytes and 25 MB.")
    resolver.openInputStream(uri)?.use { input ->
        if (input.read() < 0) throw IllegalArgumentException("This file is empty or unreadable.")
    } ?: throw IllegalArgumentException("This file couldn't be opened.")
    val extensionMime = when (displayName.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }
    val mimeType = (resolver.getType(uri)?.lowercase()?.takeIf { it in ALLOWED_JAM_AUDIO_MIMES } ?: extensionMime)
        ?: throw IllegalArgumentException("Choose an MP3, M4A, AAC, FLAC, OGG, or WAV file.")
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(resolver, uri)
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (title.isBlank() || artist.isBlank() || duration <= 0L) {
            throw IllegalArgumentException("This audio file needs readable title, artist, and duration metadata.")
        }
        val artwork = retriever.embeddedPicture?.let { bytes ->
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
        val candidate = com.example.jam.JamTrack("", title.take(180), artist.take(180), album.take(180), duration)
        return LocalAudioSelection(
            uri = uri,
            track = candidate.copy(sharedTrackId = com.example.jam.sharedTrackId(candidate)),
            mimeType = mimeType,
            sizeBytes = size,
            artwork = artwork
        )
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (_: Throwable) {
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
