package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.Player
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import com.example.data.model.Song
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Lyrics
import com.example.service.PlaybackState
import com.example.ui.components.AlbumArtView
import com.example.ui.components.AudioOutputSwitcherSheet
import com.example.ui.theme.*
import com.example.util.AudioRouteDevice
import com.example.util.AudioRouteInfo
import com.example.util.HapticUtils
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.TranscribeLanguage
import com.example.service.transcription.TranscribeLyricsPreview
import com.example.ui.components.TranscribeLanguageDialog
import com.example.ui.components.TranscribePreviewDialog
import com.example.ui.components.LyricsOffsetDialog
import java.io.File

@Composable
fun NowPlayingScreen(
    playbackState: PlaybackState,
    currentLyrics: Lyrics?,
    audioRouteInfo: AudioRouteInfo,
    availableAudioDevices: List<AudioRouteDevice> = emptyList(),
    onSelectAudioDevice: (AudioRouteDevice) -> Unit = {},
    onLaunchSystemOutputSwitcher: () -> Unit = {},
    sleepTimerState: com.example.service.SleepTimerState = com.example.service.SleepTimerState(),
    dynamicArtworkColor: Color? = null,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onFavoriteToggle: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onStartSleepTimer: (Int, Boolean) -> Unit = { _, _ -> },
    onStartSongCountTimer: (Int) -> Unit = {},
    onAddSleepTimerMinutes: (Int) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onCustomLrcSelected: (String) -> Unit,
    onReorderQueue: (Int, Int) -> Unit = { _, _ -> },
    onUpdateQueue: (List<Song>) -> Unit = {},
    onRemoveFromQueue: (Int) -> Unit = {},
    onRemoveDuplicatesFromQueue: (() -> Unit)? = null,
    onJumpToQueueItem: (Int) -> Unit = {},
    onPlayNext: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onGetPlaylistsContainingSong: (suspend (Long) -> List<com.example.data.model.Playlist>)? = null,
    onRemoveFromPlaylist: ((Long, Long) -> Unit)? = null,
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    onForceFetchLyrics: (Song) -> Unit = {},
    onSearchLyricsCandidates: (String, Boolean) -> Unit = { _, _ -> },
    lyricsSearchResults: List<com.example.service.LyricsCandidate> = emptyList(),
    isSearchingLyrics: Boolean = false,
    onApplyLyricsCandidate: (com.example.service.LyricsCandidate) -> Unit = {},
    waveformStyle: String = "SNAKE",
    nowPlayingLayout: String = "MODERN_BENTO",
    playbackPositionFlow: StateFlow<Long>? = null,
    keepScreenAwake: Boolean = false,
    gesturePlaybackEnabled: Boolean = true,
    lyricsAutoScroll: Boolean = true,
    onSaveLyricsOffset: ((Long, Long) -> Unit)? = null,
    onGetLyricsOffset: ((Long) -> Long)? = null,
    isTranscribeTranslateEnabled: Boolean = false,
    audioTranscribeMode: String = "OFF",
    audioTranscribeTargetLang: String = "ta",
    isTranscribingLyrics: Boolean = false,
    transcribeStatusMessage: String? = null,
    transcribeErrorMessage: String? = null,
    canUndoLyrics: Boolean = false,
    transcribeLyricsPreview: TranscribeLyricsPreview? = null,
    onSaveTranscribedLyrics: (TranscribeLyricsPreview) -> Unit = {},
    onDiscardTranscribedLyrics: () -> Unit = {},
    onTranslateLyrics: (Song, TranscribeLanguage) -> Unit = { _, _ -> },
    onTranscribeAudio: (Song, TranscribeLanguage) -> Unit = { _, _ -> },
    onUndoLyricsChanges: (Long) -> Unit = {},
    onClearTranscribeError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong
    val songDuration = if (playbackState.durationMs > 0L) playbackState.durationMs else (song?.durationMs ?: 0L)
    // Position is collected by the active layout and lyrics leaves so the whole screen shell
    // does not recompose on every playback tick.
    val livePositionMs: Long = if (songDuration > 0L) playbackState.positionMs.coerceIn(0L, songDuration)
        else playbackState.positionMs.coerceAtLeast(0L)
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    if (song == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BentoBackground)
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MusicOff,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = "No track playing",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Select a track from your library or a playlist to start listening.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        try {
                            onDismiss()
                        } catch (e: Exception) {
                            // Safe fallback
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("Return to Library", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }
    var showLyricsView by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showRemoveFromPlaylistDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showSearchLyricsDialog by remember { mutableStateOf(false) }
    var showAudioOutputSheet by remember { mutableStateOf(false) }
    var userIsScrollingLyrics by rememberSaveable { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Keep screen awake when lyrics are displayed on screen (in portrait overlay or landscape split view)
    val lyricsVisibleOnScreen = showLyricsView || isLandscape
    DisposableEffect(keepScreenAwake, lyricsVisibleOnScreen) {
        if (keepScreenAwake && lyricsVisibleOnScreen && activity != null) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (activity != null) {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Search Lyrics Dialog
    if (showSearchLyricsDialog) {
        val initialQuery = "${song.displayTitle} ${song.artist.takeIf { it != "<unknown>" && it != "Unknown Artist" } ?: ""}".trim()
        var searchQueryInput by remember(song.id) { 
            mutableStateOf(initialQuery) 
        }
        
        LaunchedEffect(song.id, showSearchLyricsDialog) {
            // Automatically searches if no candidates exist yet for this song
            if (lyricsSearchResults.isEmpty() && !isSearchingLyrics) {
                onSearchLyricsCandidates(searchQueryInput, false)
            }
        }

        val searchDialogDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

        AlertDialog(
            onDismissRequest = { showSearchLyricsDialog = false },
            containerColor = if (searchDialogDark) Color(0xFF1E1E2C) else Color.White,
            title = {
                Text(
                    text = "Find Lyrics Online",
                    color = if (searchDialogDark) Color.White else Color(0xFF0F172A),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    OutlinedTextField(
                        value = searchQueryInput,
                        onValueChange = { searchQueryInput = it },
                        label = {
                            Text(
                                text = "Song Title / Artist",
                                color = if (searchDialogDark) Color.White.copy(alpha = 0.7f) else Color(0xFF64748B)
                            )
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { onSearchLyricsCandidates(searchQueryInput, true) }
                        ),
                        trailingIcon = {
                            Row {
                                if (searchQueryInput.isNotEmpty()) {
                                    IconButton(onClick = { searchQueryInput = "" }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Clear text",
                                            tint = if (searchDialogDark) Color.White.copy(alpha = 0.7f) else Color(0xFF64748B)
                                        )
                                    }
                                }
                                IconButton(onClick = { onSearchLyricsCandidates(searchQueryInput, true) }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = BentoPrimary)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = if (searchDialogDark) Color.White else Color(0xFF0F172A),
                            unfocusedTextColor = if (searchDialogDark) Color.White else Color(0xFF0F172A),
                            focusedBorderColor = BentoPrimary,
                            unfocusedBorderColor = if (searchDialogDark) Color.White.copy(alpha = 0.2f) else Color(0xFFCBD5E1),
                            focusedContainerColor = if (searchDialogDark) Color(0xFF161622) else Color(0xFFF8FAFC),
                            unfocusedContainerColor = if (searchDialogDark) Color(0xFF161622) else Color(0xFFF8FAFC),
                            cursorColor = BentoPrimary,
                            focusedLabelColor = BentoPrimary,
                            unfocusedLabelColor = if (searchDialogDark) Color.White.copy(alpha = 0.7f) else Color(0xFF64748B)
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = if (searchDialogDark) Color.White else Color(0xFF0F172A),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isSearchingLyrics) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = BentoPrimary)
                        }
                    } else if (lyricsSearchResults.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No lyrics candidates found.\nTry editing the title or artist name.",
                                color = if (searchDialogDark) BentoTextSecondary else Color(0xFF64748B),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(lyricsSearchResults) { candidate ->
                                val candidateCardBg = if (searchDialogDark) Color(0xFF222232) else Color(0xFFF8FAFC)
                                val candidateCardBorder = if (searchDialogDark) Color.White.copy(alpha = 0.12f) else Color(0xFFE2E8F0)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onApplyLyricsCandidate(candidate)
                                            showSearchLyricsDialog = false
                                        },
                                    colors = CardDefaults.cardColors(containerColor = candidateCardBg),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, candidateCardBorder),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = candidate.trackName,
                                                fontWeight = FontWeight.Bold,
                                                color = if (searchDialogDark) Color.White else Color(0xFF0F172A),
                                                fontSize = 14.sp,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            if (candidate.isSynced) {
                                                Surface(
                                                    color = BentoPrimary.copy(alpha = 0.18f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Sync,
                                                            contentDescription = null,
                                                            tint = BentoPrimary,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Text(
                                                            text = "Synced",
                                                            fontSize = 11.sp,
                                                            color = BentoPrimary,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            } else {
                                                Surface(
                                                    color = if (searchDialogDark) Color.White.copy(alpha = 0.12f) else Color(0xFFE2E8F0),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.TextFields,
                                                            contentDescription = null,
                                                            tint = if (searchDialogDark) Color.White.copy(alpha = 0.7f) else Color(0xFF64748B),
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Text(
                                                            text = "Plain Text",
                                                            fontSize = 11.sp,
                                                            color = if (searchDialogDark) Color.White else Color(0xFF334155),
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${candidate.artistName}${if (candidate.albumName.isNotBlank()) " • " + candidate.albumName else ""}",
                                            color = if (searchDialogDark) Color.White.copy(alpha = 0.7f) else Color(0xFF64748B),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchLyricsDialog = false }) {
                    Text("Close", color = BentoPrimary)
                }
            }
        )
    }

    // Intercept back button when Now Playing is open: close any active sub-sheet/dialog first, otherwise dismiss Now Playing
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            showRemoveFromPlaylistDialog -> showRemoveFromPlaylistDialog = false
            showSearchLyricsDialog -> showSearchLyricsDialog = false
            showLyricsView -> showLyricsView = false
            showQueueSheet -> showQueueSheet = false
            showTimerDialog -> showTimerDialog = false
            showOptionsMenu -> showOptionsMenu = false
            else -> onDismiss()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val lrcPickerLauncher = rememberLauncherForActivityResult(
        contract = remember {
            object : androidx.activity.result.contract.ActivityResultContract<Unit, Uri?>() {
                override fun createIntent(context: android.content.Context, input: Unit): android.content.Intent {
                    return android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(
                            android.content.Intent.EXTRA_MIME_TYPES,
                            arrayOf(
                                "text/plain",
                                "application/x-lrc",
                                "text/x-lrc",
                                "application/lrc",
                                "text/lrc"
                            )
                        )
                    }
                }

                override fun parseResult(resultCode: Int, intent: android.content.Intent?): Uri? {
                    return if (intent == null || resultCode != android.app.Activity.RESULT_OK) null else intent.data
                }
            }
        }
    ) { uri: Uri? ->
        if (uri != null) {
            var fileName = ""
            try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) fileName = cursor.getString(idx) ?: ""
                    }
                }
            } catch (_: Exception) {}
            if (fileName.isBlank()) {
                fileName = uri.lastPathSegment?.substringAfterLast('/') ?: ""
            }
            val cleanName = try { android.net.Uri.decode(fileName) } catch (_: Exception) { fileName }
            val mimeType = try { context.contentResolver.getType(uri) ?: "" } catch (_: Exception) { "" }
            if (mimeType.startsWith("audio/") || cleanName.lowercase().endsWith(".mp3") || cleanName.lowercase().endsWith(".m4a") || cleanName.lowercase().endsWith(".flac") || cleanName.lowercase().endsWith(".wav") || cleanName.lowercase().endsWith(".ogg")) {
                android.widget.Toast.makeText(context, "Audio files cannot be selected for lyrics. Only .lrc files are supported.", android.widget.Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            if (!cleanName.lowercase().endsWith(".lrc")) {
                android.widget.Toast.makeText(context, "Invalid format. Only .lrc lyrics files are supported.", android.widget.Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader(Charsets.UTF_8).readText()
                    if (text.isNotBlank()) {
                        onCustomLrcSelected(text)
                        android.widget.Toast.makeText(context, "Lyrics loaded successfully", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(context, "Selected file is empty", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to read lyrics file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val effectiveDurationMs = if (playbackState.durationMs > 0L) playbackState.durationMs else (playbackState.currentSong?.durationMs ?: 0L)
    val formattedDuration = remember(effectiveDurationMs) {
        val totalSec = effectiveDurationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        String.format("%d:%02d", min, sec)
    }

    val currentBg = BentoBackground
    val isDarkNowPlayingBg = (currentBg.red * 0.299f + currentBg.green * 0.587f + currentBg.blue * 0.114f) < 0.5f
    val topPunchHoleCover = if (isDarkNowPlayingBg) Color.Black else currentBg
    val ambientThemeColor = dynamicArtworkColor ?: BentoPrimary
    val bgBrush = remember(ambientThemeColor, currentBg, topPunchHoleCover) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            0.0f to topPunchHoleCover,
            0.05f to topPunchHoleCover,
            0.16f to ambientThemeColor.copy(alpha = 0.42f),
            0.52f to ambientThemeColor.copy(alpha = 0.18f),
            1.0f to currentBg
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(currentBg)
            .background(bgBrush)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { /* Consume clicks */ }
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -30f && !showLyricsView && !isLandscape) {
                        showLyricsView = true
                    } else if (dragAmount > 30f && !showLyricsView) {
                        onDismiss()
                    }
                }
            }
    ) {
        val currentLayout = com.example.ui.theme.NowPlayingLayout.fromId(nowPlayingLayout)

        if (isLandscape) {
            // Horizontal Landscape Split View: Exact 50/50 equal halves
            // Left Half: Song image, title, seekbar, and full playback controls
            // Right Half: High-visibility synced/plain lyrics with search & LRC tools
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // LEFT HALF (50%): Song Image & Complete Controls
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(BentoCardSurface.copy(alpha = 0.95f))
                        .border(1.dp, BentoBorder.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header: Dismiss, Title & Artist, Favorite, and Options Menu
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(BentoCardPurple)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Collapse",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = song.displayTitle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${song.displayArtist} • ${song.displayAlbum}",
                                fontSize = 12.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onFavoriteToggle() },
                                modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(BentoCardPurple)
                            ) {
                                Icon(
                                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (song.isFavorite) BentoAccentPinkText else BentoTextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { showOptionsMenu = true },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(BentoCardPurple)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = BentoTextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Centered Album Artwork
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        com.example.ui.components.AlbumArtGestureBox(
                            enabled = gesturePlaybackEnabled,
                            isPlaying = playbackState.isPlaying,
                            onTogglePlayPause = onTogglePlayPause,
                            onSkipPrevious = onSkipPrevious,
                            onSkipNext = onSkipNext
                        ) {
                            AlbumArtView(
                                song = song,
                                modifier = Modifier
                                    .size(135.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                                cornerRadius = 16
                            )
                        }
                    }

                    // Seekbar & Waveform Progress
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        com.example.ui.components.NowPlayingWaveformVisualizer(
                            trackId = song.id,
                            progressMs = livePositionMs,
                            durationMs = playbackState.durationMs,
                            isPlaying = playbackState.isPlaying,
                            style = waveformStyle,
                            accentColor = BentoPrimary,
                            onSeekTo = onSeekTo,
                            playbackPositionFlow = playbackPositionFlow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            com.example.ui.components.PlaybackPositionText(playbackPositionFlow, livePositionMs, effectiveDurationMs, fontSize = 11.sp, color = BentoTextSecondary)
                            Text(text = formattedDuration, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Playback Controls Row (Shuffle, Prev, Play/Pause, Next, Repeat, Queue)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (playbackState.isShuffleEnabled) BentoPrimary else BentoTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                com.example.util.HapticUtils.performClickHaptic(context)
                                onSkipPrevious()
                            },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = BentoTextPrimary, modifier = Modifier.size(30.dp))
                        }

                        Box(
                            modifier = Modifier
                                .width(64.dp)
                                .height(42.dp)
                                .clip(CircleShape)
                                .background(BentoPrimary)
                                .clickable {
                                    com.example.util.HapticUtils.performClickHaptic(context)
                                    onTogglePlayPause()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = BentoOnPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                com.example.util.HapticUtils.performClickHaptic(context)
                                onSkipNext()
                            },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = BentoTextPrimary, modifier = Modifier.size(30.dp))
                        }

                        IconButton(
                            onClick = {
                                com.example.util.HapticUtils.performClickHaptic(context)
                                onToggleRepeat()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (playbackState.repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat",
                                tint = if (playbackState.repeatMode != 0) BentoPrimary else BentoTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(onClick = { showQueueSheet = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = BentoTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // RIGHT HALF (50%): High-Visibility Lyrics Container
                val isLandscapeDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isLandscapeDark) Color(0xFF09090E) else Color.White)
                        .border(1.dp, if (isLandscapeDark) BentoBorder.copy(alpha = 0.35f) else Color(0xFFE2E8F0), RoundedCornerShape(20.dp))
                        .padding(12.dp)
                ) {
                    LyricsPanelContent(
                        song = song,
                        currentLyrics = currentLyrics,
                        playbackState = playbackState,
                        positionMs = livePositionMs,
                        showLyricsView = true,
                        isLandscape = true,
                        userIsScrollingLyrics = userIsScrollingLyrics,
                        lyricsAutoScroll = lyricsAutoScroll,
                        onSaveLyricsOffset = onSaveLyricsOffset,
                        onGetLyricsOffset = onGetLyricsOffset,
                        ambientThemeColor = ambientThemeColor,
                        onUserIsScrollingChange = { userIsScrollingLyrics = it },
                        onSeekTo = onSeekTo,
                        onForceFetchLyrics = { onForceFetchLyrics(song) },
                        onOpenSearchLyrics = { showSearchLyricsDialog = true },
                        onPickLrcFile = { lrcPickerLauncher.launch(Unit) },
                        onCloseLyrics = { showLyricsView = false },
                        coroutineScope = coroutineScope,
                        isTranscribeTranslateEnabled = isTranscribeTranslateEnabled,
                        audioTranscribeMode = audioTranscribeMode,
                        audioTranscribeTargetLang = audioTranscribeTargetLang,
                        isTranscribingLyrics = isTranscribingLyrics,
                        transcribeStatusMessage = transcribeStatusMessage,
                        transcribeErrorMessage = transcribeErrorMessage,
                        canUndoLyrics = canUndoLyrics,
                        transcribeLyricsPreview = transcribeLyricsPreview,
                        onSaveTranscribedLyrics = onSaveTranscribedLyrics,
                        onDiscardTranscribedLyrics = onDiscardTranscribedLyrics,
                        onTranslateLyrics = onTranslateLyrics,
                        onTranscribeAudio = onTranscribeAudio,
                        onUndoLyricsChanges = onUndoLyricsChanges,
                        onClearTranscribeError = onClearTranscribeError,
                        playbackPositionFlow = playbackPositionFlow
                    )
                }
            }
        } else {
            when (currentLayout) {
                com.example.ui.theme.NowPlayingLayout.VINYL_TURNTABLE -> {
                    com.example.ui.screens.layouts.NowPlayingVinylTurntable(
                        playbackState = playbackState,
                        positionMs = livePositionMs,
                        currentLyrics = currentLyrics,
                        audioRouteInfo = audioRouteInfo,
                        onDismiss = onDismiss,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        onSeekTo = onSeekTo,
                        onFavoriteToggle = onFavoriteToggle,
                        onToggleShuffle = onToggleShuffle,
                        onToggleRepeat = onToggleRepeat,
                        onOpenAudioOutputSwitcher = { showAudioOutputSheet = true },
                        onOpenQueueSheet = { showQueueSheet = true },
                        onOpenLyricsSheet = { showLyricsView = true },
                        onOpenSleepTimer = { showTimerDialog = true },
                        onOpenOptionsMenu = { showOptionsMenu = true },
                        waveformStyle = waveformStyle,
                        gesturePlaybackEnabled = gesturePlaybackEnabled,
                        playbackPositionFlow = playbackPositionFlow,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                com.example.ui.theme.NowPlayingLayout.MINIMAL_GLASS -> {
                    com.example.ui.screens.layouts.NowPlayingMinimalGlass(
                        playbackState = playbackState,
                        positionMs = livePositionMs,
                        currentLyrics = currentLyrics,
                        audioRouteInfo = audioRouteInfo,
                        onDismiss = onDismiss,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        onSeekTo = onSeekTo,
                        onFavoriteToggle = onFavoriteToggle,
                        onToggleShuffle = onToggleShuffle,
                        onToggleRepeat = onToggleRepeat,
                        onOpenAudioOutputSwitcher = { showAudioOutputSheet = true },
                        onOpenQueueSheet = { showQueueSheet = true },
                        onOpenLyricsSheet = { showLyricsView = true },
                        onOpenSleepTimer = { showTimerDialog = true },
                        onOpenOptionsMenu = { showOptionsMenu = true },
                        waveformStyle = waveformStyle,
                        gesturePlaybackEnabled = gesturePlaybackEnabled,
                        playbackPositionFlow = playbackPositionFlow,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                com.example.ui.theme.NowPlayingLayout.NEON_WAVEFORM -> {
                    com.example.ui.screens.layouts.NowPlayingNeonWaveform(
                        playbackState = playbackState,
                        positionMs = livePositionMs,
                        currentLyrics = currentLyrics,
                        audioRouteInfo = audioRouteInfo,
                        onDismiss = onDismiss,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        onSeekTo = onSeekTo,
                        onFavoriteToggle = onFavoriteToggle,
                        onToggleShuffle = onToggleShuffle,
                        onToggleRepeat = onToggleRepeat,
                        onOpenAudioOutputSwitcher = { showAudioOutputSheet = true },
                        onOpenQueueSheet = { showQueueSheet = true },
                        onOpenLyricsSheet = { showLyricsView = true },
                        onOpenSleepTimer = { showTimerDialog = true },
                        onOpenOptionsMenu = { showOptionsMenu = true },
                        waveformStyle = waveformStyle,
                        gesturePlaybackEnabled = gesturePlaybackEnabled,
                        playbackPositionFlow = playbackPositionFlow,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                com.example.ui.theme.NowPlayingLayout.MODERN_BENTO -> {
                    // Full-Bleed Immersive Modern Bento View (matching reference design)
                    val bottomScrimColor = remember(ambientThemeColor) {
                        Color(
                            red = (ambientThemeColor.red * 0.22f + 0.06f).coerceIn(0f, 1f),
                            green = (ambientThemeColor.green * 0.22f + 0.06f).coerceIn(0f, 1f),
                            blue = (ambientThemeColor.blue * 0.24f + 0.08f).coerceIn(0f, 1f),
                            alpha = 1f
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(bottomScrimColor)
                    ) {
                        // 1. Upper Full-Bleed Edge-to-Edge Artwork with Gesture Support
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.66f)
                                .align(Alignment.TopCenter)
                        ) {
                            com.example.ui.components.AlbumArtGestureBox(
                                enabled = gesturePlaybackEnabled,
                                isPlaying = playbackState.isPlaying,
                                onTogglePlayPause = onTogglePlayPause,
                                onSkipPrevious = onSkipPrevious,
                                onSkipNext = onSkipNext,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                AlbumArtView(
                                    song = song,
                                    modifier = Modifier.fillMaxSize(),
                                    cornerRadius = 0
                                )
                            }

                            // Top punch-hole black cover + smooth multi-stop gradient fade from artwork into lower controls
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.0f to Color.Black,
                                            0.07f to Color.Black.copy(alpha = 0.94f),
                                            0.15f to ambientThemeColor.copy(alpha = 0.22f),
                                            0.26f to Color.Transparent,
                                            0.54f to Color.Transparent,
                                            0.78f to bottomScrimColor.copy(alpha = 0.72f),
                                            1.0f to bottomScrimColor
                                        )
                                    )
                            )
                        }

                        // Subtle Top Collapse Button Over Full-Bleed Artwork
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                                .align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = onDismiss,
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.28f),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Collapse",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            if (sleepTimerState.isActive) {
                                Surface(
                                    onClick = { showTimerDialog = true },
                                    shape = RoundedCornerShape(50),
                                    color = Color.Black.copy(alpha = 0.35f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Timer,
                                            contentDescription = null,
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "Timer Active",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Lower Seamless Controls Overlay (matching reference layout)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Title + Artist on Left, Circular Options (⋮) + Favorite (♡) on Right
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp)
                                ) {
                                    Text(
                                        text = song.displayTitle,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = song.displayArtist,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.80f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Frosted Circular Options Button (⋮)
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            showOptionsMenu = true
                                        },
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.15f),
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Options",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    // Frosted Circular Favorite Button (♡)
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onFavoriteToggle()
                                        },
                                        shape = CircleShape,
                                        color = if (song.isFavorite) BentoAccentPinkText.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.15f),
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                contentDescription = "Favorite",
                                                tint = if (song.isFavorite) BentoAccentPinkText else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Seekbar / Waveform + Timestamps
                            Column(modifier = Modifier.fillMaxWidth()) {
                                com.example.ui.components.NowPlayingWaveformVisualizer(
                                    trackId = song.id,
                                    progressMs = livePositionMs,
                                    durationMs = playbackState.durationMs,
                                    isPlaying = playbackState.isPlaying,
                                    style = waveformStyle,
                                    accentColor = Color.White,
                                    onSeekTo = onSeekTo,
                                    playbackPositionFlow = playbackPositionFlow,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    com.example.ui.components.PlaybackPositionText(
                                        playbackPositionFlow,
                                        livePositionMs,
                                        effectiveDurationMs,
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                    Text(
                                        text = formattedDuration,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            // Large Clean Borderless Transport Controls (Rewind, Play/Pause, Fast-Forward)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onSkipPrevious()
                                    },
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FastRewind,
                                        contentDescription = "Previous",
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onTogglePlayPause()
                                    },
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    Icon(
                                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(50.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onSkipNext()
                                    },
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FastForward,
                                        contentDescription = "Next",
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            // Centered Audio Output Device Label (e.g. earbuds icon + device name)
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable {
                                            HapticUtils.performClickHaptic(context)
                                            showAudioOutputSheet = true
                                        }
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                        .testTag("audio_output_route_button"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = audioRouteInfo.icon,
                                        contentDescription = "Audio Output Device",
                                        tint = Color.White.copy(alpha = 0.78f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = audioRouteInfo.name,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.82f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Bottom Bar: Queue & Shuffle on Left, Split Frosted Pill (Output | Timer) in Center, Repeat & Lyrics on Right
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: Queue + Shuffle
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            showQueueSheet = true
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FormatListBulleted,
                                            contentDescription = "Up Next Queue",
                                            tint = Color.White.copy(alpha = 0.88f),
                                            modifier = Modifier.size(23.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onToggleShuffle()
                                        },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shuffle,
                                            contentDescription = "Shuffle",
                                            tint = if (playbackState.isShuffleEnabled) BentoPrimary else Color.White.copy(alpha = 0.55f),
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }

                                // Center: Signature Split Frosted Pill [Audio Output | Sleep Timer]
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            showAudioOutputSheet = true
                                        },
                                        shape = RoundedCornerShape(
                                            topStart = 24.dp,
                                            bottomStart = 24.dp,
                                            topEnd = 6.dp,
                                            bottomEnd = 6.dp
                                        ),
                                        color = Color.White.copy(alpha = 0.15f),
                                        modifier = Modifier
                                            .width(58.dp)
                                            .height(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.SpeakerGroup,
                                                contentDescription = "Switch Audio Output",
                                                tint = Color.White.copy(alpha = 0.92f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            showTimerDialog = true
                                        },
                                        shape = RoundedCornerShape(
                                            topStart = 6.dp,
                                            bottomStart = 6.dp,
                                            topEnd = 24.dp,
                                            bottomEnd = 24.dp
                                        ),
                                        color = if (sleepTimerState.isActive) BentoPrimary.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.15f),
                                        modifier = Modifier
                                            .width(58.dp)
                                            .height(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Timer,
                                                contentDescription = "Sleep Timer",
                                                tint = if (sleepTimerState.isActive) BentoPrimary else Color.White.copy(alpha = 0.92f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                // Right: Repeat + Lyrics Badge
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val isRepeatActive = playbackState.repeatMode != Player.REPEAT_MODE_OFF
                                    IconButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onToggleRepeat()
                                        },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                            contentDescription = "Repeat",
                                            tint = if (isRepeatActive) BentoPrimary else Color.White.copy(alpha = 0.55f),
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            showLyricsView = !showLyricsView
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ChatBubbleOutline,
                                            contentDescription = "Lyrics",
                                            tint = if (showLyricsView) BentoPrimary else Color.White.copy(alpha = 0.88f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showOptionsMenu && song != null) {
            com.example.ui.components.NowPlayingActionMenu(
                expanded = true,
                song = song,
                onDismissRequest = { showOptionsMenu = false },
                onOpenSleepTimer = {
                    showOptionsMenu = false
                    showTimerDialog = true
                },
                onAddToPlaylist = {
                    showOptionsMenu = false
                    onAddToPlaylist(song)
                },
                onRemoveFromPlaylist = {
                    showOptionsMenu = false
                    showRemoveFromPlaylistDialog = true
                },
                onPlayNext = {
                    showOptionsMenu = false
                    onPlayNext(song)
                },
                onOpenQueueSheet = {
                    showOptionsMenu = false
                    showQueueSheet = true
                },
                onChooseLrc = {
                    showOptionsMenu = false
                    lrcPickerLauncher.launch(Unit)
                },
                onSearchOnlineLyrics = {
                    showOptionsMenu = false
                    showSearchLyricsDialog = true
                },
                onEditTags = if (onEditTags != null) {
                    {
                        showOptionsMenu = false
                        onEditTags(song)
                    }
                } else null,
                onDeleteSong = {
                    showOptionsMenu = false
                    showDeleteConfirmDialog = true
                }
            )
        }

        if (showRemoveFromPlaylistDialog && song != null && onGetPlaylistsContainingSong != null && onRemoveFromPlaylist != null) {
            com.example.ui.components.RemoveFromPlaylistDialog(
                song = song,
                onGetPlaylistsContainingSong = onGetPlaylistsContainingSong,
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onDismiss = { showRemoveFromPlaylistDialog = false }
            )
        }

    // Spotify-style Swipe-up Full Overlay Lyrics Panel (Works across all UI Themes & Layouts)
    AnimatedVisibility(
        visible = showLyricsView,
        enter = slideInVertically { it },
        exit = slideOutVertically { it }
    ) {
        val isCurrentDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val lyricsBg = if (isCurrentDark) Color(0xFF09090E) else Color.White

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(lyricsBg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 40f) {
                            showLyricsView = false
                        }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            LyricsPanelContent(
                song = song,
                currentLyrics = currentLyrics,
                playbackState = playbackState,
                positionMs = livePositionMs,
                showLyricsView = showLyricsView,
                userIsScrollingLyrics = userIsScrollingLyrics,
                lyricsAutoScroll = lyricsAutoScroll,
                onSaveLyricsOffset = onSaveLyricsOffset,
                onGetLyricsOffset = onGetLyricsOffset,
                ambientThemeColor = ambientThemeColor,
                onUserIsScrollingChange = { userIsScrollingLyrics = it },
                onSeekTo = onSeekTo,
                onForceFetchLyrics = { onForceFetchLyrics(song) },
                onOpenSearchLyrics = { showSearchLyricsDialog = true },
                onPickLrcFile = { lrcPickerLauncher.launch(Unit) },
                onCloseLyrics = { showLyricsView = false },
                coroutineScope = coroutineScope,
                isTranscribeTranslateEnabled = isTranscribeTranslateEnabled,
                audioTranscribeMode = audioTranscribeMode,
                audioTranscribeTargetLang = audioTranscribeTargetLang,
                isTranscribingLyrics = isTranscribingLyrics,
                transcribeStatusMessage = transcribeStatusMessage,
                transcribeErrorMessage = transcribeErrorMessage,
                canUndoLyrics = canUndoLyrics,
                transcribeLyricsPreview = transcribeLyricsPreview,
                onSaveTranscribedLyrics = onSaveTranscribedLyrics,
                onDiscardTranscribedLyrics = onDiscardTranscribedLyrics,
                onTranslateLyrics = onTranslateLyrics,
                onTranscribeAudio = onTranscribeAudio,
                onUndoLyricsChanges = onUndoLyricsChanges,
                onClearTranscribeError = onClearTranscribeError,
                playbackPositionFlow = playbackPositionFlow
            )
        }
    }

        // Up Next Queue Bottom Sheet
        if (showQueueSheet) {
            com.example.ui.components.UpNextQueueSheet(
                playbackState = playbackState,
                onDismiss = { showQueueSheet = false },
                onSongClick = { index ->
                    onJumpToQueueItem(index)
                },
                onReorder = onReorderQueue,
                onUpdateQueue = onUpdateQueue,
                onRemoveFromQueue = onRemoveFromQueue,
                onToggleRepeat = onToggleRepeat,
                onToggleShuffle = onToggleShuffle,
                onRemoveDuplicates = onRemoveDuplicatesFromQueue,
                onPlayNext = onPlayNext,
                onAddToPlaylist = onAddToPlaylist
            )
        }

        // Sleep Timer Selection Dialog
        if (showTimerDialog) {
            com.example.ui.components.SleepTimerDialog(
                timerState = sleepTimerState,
                onStartTimerMinutes = { mins, finishLastSong ->
                    onStartSleepTimer(mins, finishLastSong)
                    showTimerDialog = false
                },
                onStartSongCount = { songs ->
                    onStartSongCountTimer(songs)
                    showTimerDialog = false
                },
                onAddMinutes = { extraMins ->
                    onAddSleepTimerMinutes(extraMins)
                },
                onCancelTimer = {
                    onCancelSleepTimer()
                    showTimerDialog = false
                },
                onDismiss = { showTimerDialog = false }
            )
        }

        // Audio Output Switcher Bottom Sheet
        if (showAudioOutputSheet) {
            AudioOutputSwitcherSheet(
                audioRouteInfo = audioRouteInfo,
                availableDevices = availableAudioDevices,
                onSelectDevice = { device ->
                    onSelectAudioDevice(device)
                },
                onLaunchSystemSwitcher = {
                    onLaunchSystemOutputSwitcher()
                },
                onDismiss = { showAudioOutputSheet = false }
            )
        }

        // Delete Song Confirmation Dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete ${song.displayTitle}?", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "This will permanently delete the audio file from your device storage and remove it from your library, playlists, and queue.",
                        color = BentoTextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirmDialog = false
                            onDeleteSong(song)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BentoAccentPinkText)
                    ) {
                        Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel", color = BentoTextSecondary)
                    }
                },
                containerColor = BentoCardSurface,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Preview Transcribed / Translated Lyrics Dialog
        if (transcribeLyricsPreview != null) {
            TranscribePreviewDialog(
                preview = transcribeLyricsPreview,
                onSave = { editedPreview -> onSaveTranscribedLyrics(editedPreview) },
                onDiscard = onDiscardTranscribedLyrics
            )
        }
    }
}

fun cleanLyricDisplayText(text: String): String {
    var cleaned = text
    // Remove bracketed, parenthesized or angled timestamps like [01:23.45], (01:23.45), <01:23.45>, [01:23], (01:23)
    cleaned = cleaned.replace(Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]"""), "")
    cleaned = cleaned.replace(Regex("""\(\d{1,3}:\d{2}(?:[.:]\d{1,3})?\)"""), "")
    cleaned = cleaned.replace(Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>"""), "")
    // Remove standalone leading or trailing timestamp like "01:23" or "01:23.45"
    cleaned = cleaned.replace(Regex("""^\s*\d{1,3}:\d{2}(?:[.:]\d{1,3})?\s*[-–—:]?\s*"""), "")
    cleaned = cleaned.replace(Regex("""\s*[-–—:]?\s*\d{1,3}:\d{2}(?:[.:]\d{1,3})?\s*$"""), "")
    // Remove any offset indicators like [offset: ...], (offset: ...), offset: +0.5s, +0.5s, +500ms
    cleaned = cleaned.replace(Regex("""\[offset:\s*[-+]?\d+(?:\.\d+)?(?:s|ms)?\]""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""\(offset:\s*[-+]?\d+(?:\.\d+)?(?:s|ms)?\)""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""\boffset:\s*[-+]?\d+(?:\.\d+)?(?:s|ms)?\b""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""\[[-+]\d+(?:\.\d+)?(?:s|ms)\]""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""\([-+]\d+(?:\.\d+)?(?:s|ms)\)""", RegexOption.IGNORE_CASE), "")
    // Remove any metadata tags like [by: ...], [ti: ...], etc.
    cleaned = cleaned.replace(Regex("""\[(?:ti|ar|al|by|length|re|ve):[^\]]*\]""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.trim()
    return if (cleaned.isEmpty()) "♪" else cleaned
}

@Composable
private fun LyricsPanelContent(
    song: Song,
    currentLyrics: Lyrics?,
    playbackState: PlaybackState,
    positionMs: Long = playbackState.positionMs,
    playbackPositionFlow: kotlinx.coroutines.flow.StateFlow<Long>? = null,
    showLyricsView: Boolean,
    userIsScrollingLyrics: Boolean,
    isLandscape: Boolean = false,
    lyricsAutoScroll: Boolean = true,
    onSaveLyricsOffset: ((Long, Long) -> Unit)? = null,
    onGetLyricsOffset: ((Long) -> Long)? = null,
    ambientThemeColor: Color = BentoPrimary,
    onUserIsScrollingChange: (Boolean) -> Unit,
    onSeekTo: (Long) -> Unit,
    onForceFetchLyrics: () -> Unit,
    onOpenSearchLyrics: () -> Unit = {},
    onPickLrcFile: () -> Unit,
    onCloseLyrics: () -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    isTranscribeTranslateEnabled: Boolean = false,
    audioTranscribeMode: String = "OFF",
    audioTranscribeTargetLang: String = "ta",
    isTranscribingLyrics: Boolean = false,
    transcribeStatusMessage: String? = null,
    transcribeErrorMessage: String? = null,
    canUndoLyrics: Boolean = false,
    transcribeLyricsPreview: TranscribeLyricsPreview? = null,
    onSaveTranscribedLyrics: (TranscribeLyricsPreview) -> Unit = {},
    onDiscardTranscribedLyrics: () -> Unit = {},
    onTranslateLyrics: (Song, TranscribeLanguage) -> Unit = { _, _ -> },
    onTranscribeAudio: (Song, TranscribeLanguage) -> Unit = { _, _ -> },
    onUndoLyricsChanges: (Long) -> Unit = {},
    onClearTranscribeError: () -> Unit = {}
) {
    val livePositionMs = playbackPositionFlow?.collectAsStateWithLifecycle()?.value ?: positionMs
    val context = LocalContext.current
    var syncOffsetMs by remember(song.id) {
        mutableLongStateOf(onGetLyricsOffset?.invoke(song.id) ?: 0L)
    }
    LaunchedEffect(song.id, currentLyrics) {
        syncOffsetMs = onGetLyricsOffset?.invoke(song.id) ?: 0L
    }
    var showOffsetDialog by remember { mutableStateOf(false) }
    var showTranscribeDialog by remember { mutableStateOf(false) }
    var transcribeDialogInitialMode by remember { mutableStateOf(AudioTranscribeMode.TRANSLATE) }

    val effectivePositionMs = (livePositionMs + syncOffsetMs).coerceAtLeast(0L)
    val syncedLines = remember(currentLyrics) {
        try {
            currentLyrics?.parseSyncedLines() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Box(modifier = Modifier.fillMaxSize().background(if (isDarkTheme) Color(0xFF09090E) else Color.White)) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Lyrics",
                            fontSize = if (isLandscape) 18.sp else 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDarkTheme) Color.White else Color(0xFF0F172A)
                        )
                        if (syncedLines.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .background(if (isDarkTheme) BentoPrimary.copy(alpha = 0.2f) else BentoPrimary.copy(alpha = 0.12f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("SYNCED", fontSize = 10.sp, color = BentoPrimary, fontWeight = FontWeight.Bold)
                            }
                        } else if (!currentLyrics?.plainLyrics.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .background(if (isDarkTheme) BentoCardPurple else Color(0xFFF1F5F9), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("PLAIN TEXT", fontSize = 10.sp, color = if (isDarkTheme) BentoTextSecondary else Color(0xFF475569), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Text(
                        text = song.displayTitle,
                        fontSize = 12.sp,
                        color = BentoPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val hasLyrics = syncedLines.isNotEmpty() || !currentLyrics?.plainLyrics.isNullOrBlank()

                    // Top Bar Search Lyrics Icon Button
                    IconButton(
                        onClick = {
                            com.example.util.HapticUtils.performClickHaptic(context)
                            onOpenSearchLyrics()
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isDarkTheme) BentoCardPurple else Color(0xFFF1F5F9))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, if (isDarkTheme) Color.Transparent else Color(0xFFE2E8F0)), CircleShape)
                            .testTag("lyrics_top_bar_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search lyrics",
                            tint = if (isDarkTheme) BentoTextPrimary else Color(0xFF1E293B),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Offset button (on tab bar - text represent)
                    if (syncedLines.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                com.example.util.HapticUtils.performClickHaptic(context)
                                showOffsetDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (syncOffsetMs != 0L) BentoPrimary else (if (isDarkTheme) Color.White else Color(0xFF1E293B))
                            ),
                            modifier = Modifier.testTag("lyrics_offset_tab_button")
                        ) {
                            val offsetText = if (syncOffsetMs == 0L) {
                                "Offset"
                            } else {
                                val sec = syncOffsetMs / 1000.0
                                if (syncOffsetMs > 0) "Offset +${"%.1f".format(sec)}s" else "Offset ${"%.1f".format(sec)}s"
                            }
                            Text(offsetText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isTranscribeTranslateEnabled) {
                        IconButton(
                            onClick = {
                                transcribeDialogInitialMode = if (hasLyrics) AudioTranscribeMode.TRANSLATE else AudioTranscribeMode.TRANSCRIBE
                                showTranscribeDialog = true
                            },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.16f))
                        ) {
                            Icon(
                                imageVector = if (hasLyrics) Icons.Default.Translate else Icons.Default.GraphicEq,
                                contentDescription = if (hasLyrics) "Translate lyrics (💬)" else "Transcribe song audio (🎼)",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    TextButton(
                        onClick = onPickLrcFile,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = BentoPrimary)
                    ) {
                        Text(".LRC File", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    if (!isLandscape) {
                        IconButton(
                            onClick = onCloseLyrics,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (isDarkTheme) BentoCardPurple else Color(0xFFF1F5F9))
                                .border(androidx.compose.foundation.BorderStroke(1.dp, if (isDarkTheme) Color.Transparent else Color(0xFFE2E8F0)), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close Lyrics",
                                tint = if (isDarkTheme) BentoTextPrimary else Color(0xFF1E293B),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // AI Transcription / Translation Progress State
            AnimatedVisibility(
                visible = isTranscribingLyrics,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E5FF),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = transcribeStatusMessage ?: "Processing lyrics...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = BentoTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // AI Transcription / Translation Error Banner
            AnimatedVisibility(
                visible = transcribeErrorMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val hasLyrics = syncedLines.isNotEmpty() || !currentLyrics?.plainLyrics.isNullOrBlank()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    color = Color(0xFFFF5252).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                        Text(
                            text = transcribeErrorMessage ?: "An error occurred",
                            fontSize = 11.5.sp,
                            color = BentoTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                onClearTranscribeError()
                                transcribeDialogInitialMode = if (hasLyrics) AudioTranscribeMode.TRANSLATE else AudioTranscribeMode.TRANSCRIBE
                                showTranscribeDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Retry", fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = onClearTranscribeError,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = BentoTextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            if (syncedLines.isNotEmpty()) {
                val listState = rememberLazyListState()
                var userHasManuallyScrolled by remember(song.id) { mutableStateOf(false) }
                val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
                val activeIndex = syncedLines.indexOfLast { effectivePositionMs >= it.first }.coerceAtLeast(0)

                // Track manually viewed lyric index (closest to vertical center of list)
                val manuallyViewedIndex by remember {
                    derivedStateOf {
                        val layoutInfo = listState.layoutInfo
                        val visible = layoutInfo.visibleItemsInfo
                        if (visible.isEmpty()) activeIndex
                        else {
                            val center = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                            visible.minByOrNull { item ->
                                val itemCenter = item.offset + item.size / 2
                                kotlin.math.abs(itemCenter - center)
                            }?.index ?: activeIndex
                        }
                    }
                }

                // Detect when user manually drags/scrolls the list
                LaunchedEffect(isUserDragging) {
                    if (isUserDragging) {
                        userHasManuallyScrolled = true
                        onUserIsScrollingChange(true)
                    }
                }

                // Initial auto-scroll when opening lyrics or song changes
                LaunchedEffect(song.id, showLyricsView) {
                    if (showLyricsView) {
                        userHasManuallyScrolled = false
                        onUserIsScrollingChange(false)
                        if (lyricsAutoScroll && activeIndex in syncedLines.indices) {
                            listState.animateScrollToItem(activeIndex)
                        }
                    }
                }

                // Auto-scroll to current line:
                // Only scrolls when setting is enabled AND user has not manually scrolled away
                // Also updates smoothly when syncOffsetMs changes
                LaunchedEffect(activeIndex, userHasManuallyScrolled, lyricsAutoScroll, syncOffsetMs) {
                    if (lyricsAutoScroll && !userHasManuallyScrolled && activeIndex in syncedLines.indices) {
                        listState.animateScrollToItem(activeIndex)
                    }
                }

                // Re-sync to currently playing line when seeking or track changes occur
                var lastPositionMs by remember(song.id) { mutableLongStateOf(effectivePositionMs) }
                LaunchedEffect(effectivePositionMs) {
                    if (kotlin.math.abs(effectivePositionMs - lastPositionMs) > 2500L) {
                        userHasManuallyScrolled = false
                        onUserIsScrollingChange(false)
                        if (lyricsAutoScroll && activeIndex in syncedLines.indices) {
                            listState.animateScrollToItem(activeIndex)
                        }
                    }
                    lastPositionMs = effectivePositionMs
                }

                // Sync button visibility logic:
                // Auto-scroll ON: show when user has manually scrolled away
                // Auto-scroll OFF: show ONLY when user's manually viewed position differs from active playing line
                val shouldShowSyncButton by remember {
                    derivedStateOf {
                        if (lyricsAutoScroll) {
                            userHasManuallyScrolled
                        } else {
                            manuallyViewedIndex != activeIndex
                        }
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val halfHeight = maxHeight / 2
                    val lyricVerticalPadding = (halfHeight - (if (isLandscape) 24.dp else 32.dp)).coerceAtLeast(40.dp)

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        userHasManuallyScrolled = true
                                        onUserIsScrollingChange(true)
                                    },
                                    onDrag = { _, _ ->
                                        userHasManuallyScrolled = true
                                        onUserIsScrollingChange(true)
                                    }
                                )
                            },
                        contentPadding = PaddingValues(top = lyricVerticalPadding, bottom = lyricVerticalPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(syncedLines) { index, (timeMs, lineText) ->
                            val isCurrentLine = index == activeIndex
                            val lineScale by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (isCurrentLine) 1.05f else 0.95f,
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                ),
                                label = "lyricScale"
                            )

                            val activeLineBg = if (isDarkTheme) ambientThemeColor.copy(alpha = 0.32f) else ambientThemeColor.copy(alpha = 0.15f)
                            val activeLineBorder = if (isDarkTheme) ambientThemeColor.copy(alpha = 0.65f) else ambientThemeColor.copy(alpha = 0.75f)
                            val currentLineTextColor = if (isDarkTheme) Color.White else Color(0xFF0F172A)
                            val inactiveLineTextColor = if (isDarkTheme) Color.White.copy(alpha = 0.65f) else Color(0xFF334155).copy(alpha = 0.60f)
                            val cleanDisplayText = remember(lineText) { cleanLyricDisplayText(lineText) }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isCurrentLine) activeLineBg else Color.Transparent
                                    )
                                    .border(
                                        width = if (isCurrentLine) 1.5.dp else 0.dp,
                                        color = if (isCurrentLine) activeLineBorder else Color.Transparent,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable {
                                        val targetSeekMs = (timeMs - syncOffsetMs).coerceAtLeast(0L)
                                        userHasManuallyScrolled = false
                                        onUserIsScrollingChange(false)
                                        onSeekTo(targetSeekMs)
                                    }
                                    .padding(vertical = if (isLandscape) 10.dp else 12.dp, horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = cleanDisplayText,
                                        fontSize = if (isCurrentLine) (if (isLandscape) 19.sp else 22.sp) else (if (isLandscape) 14.sp else 16.sp),
                                        fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isCurrentLine) currentLineTextColor else inactiveLineTextColor,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                scaleX = lineScale
                                                scaleY = lineScale
                                                alpha = if (isCurrentLine) 1.0f else (if (isDarkTheme) 0.70f else 0.65f)
                                            }
                                    )
                                }
                            }
                        }
                    }

                    // Floating Sync, Offset, and Search buttons at bottom-left:
                    // All appear and disappear together following the exact same auto-reveal and auto-hide logic!
                    androidx.compose.animation.AnimatedVisibility(
                        visible = shouldShowSyncButton,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 16.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    com.example.util.HapticUtils.performClickHaptic(context)
                                    userHasManuallyScrolled = false
                                    onUserIsScrollingChange(false)
                                    coroutineScope.launch {
                                        if (activeIndex in syncedLines.indices) {
                                            listState.animateScrollToItem(activeIndex)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                                shape = RoundedCornerShape(20.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync to current line", tint = BentoOnPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync", color = BentoOnPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            // Offset option with icon represent alone
                            Surface(
                                onClick = {
                                    com.example.util.HapticUtils.performClickHaptic(context)
                                    showOffsetDialog = true
                                },
                                shape = CircleShape,
                                color = if (syncOffsetMs != 0L) BentoPrimary else (if (isDarkTheme) Color(0xFF1E1E2C) else Color(0xFFF1F5F9)),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = if (syncOffsetMs != 0L) BentoPrimary else (if (isDarkTheme) Color.White.copy(alpha = 0.2f) else Color(0xFFCBD5E1))
                                ),
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("lyrics_offset_sync_icon_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Adjust lyrics offset",
                                        tint = if (syncOffsetMs != 0L) BentoOnPrimary else (if (isDarkTheme) Color.White else Color(0xFF1E293B)),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Search lyrics option (with icon and search lyrics label, auto-hides and auto-reveals alongside sync and offset)
                            Button(
                                onClick = {
                                    com.example.util.HapticUtils.performClickHaptic(context)
                                    onOpenSearchLyrics()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDarkTheme) Color(0xFF1E1E2C) else Color(0xFFF1F5F9),
                                    contentColor = if (isDarkTheme) Color.White else Color(0xFF1E293B)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = if (isDarkTheme) Color.White.copy(alpha = 0.2f) else Color(0xFFCBD5E1)
                                ),
                                shape = RoundedCornerShape(20.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("lyrics_search_bottom_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search lyrics",
                                    tint = if (isDarkTheme) Color.White else Color(0xFF1E293B),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Search Lyrics",
                                    color = if (isDarkTheme) Color.White else Color(0xFF1E293B),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                // Unsynced or Plain Lyrics View with Scroll Support
                val plainLyricsText = currentLyrics?.plainLyrics
                if (!plainLyricsText.isNullOrBlank()) {
                    val plainLines = remember(plainLyricsText) { plainLyricsText.lines().filter { it.isNotBlank() } }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(plainLines) { _, lineText ->
                            val cleanLine = remember(lineText) { cleanLyricDisplayText(lineText) }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cleanLine,
                                    fontSize = if (isLandscape) 15.sp else 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDarkTheme) Color.White.copy(alpha = 0.92f) else Color(0xFF0F172A),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = onPickLrcFile,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.6f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary)
                                ) {
                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = BentoPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Change or Pick .LRC", fontSize = 12.sp, color = BentoPrimary)
                                }
                                OutlinedButton(
                                    onClick = onOpenSearchLyrics,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.6f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = BentoPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Search Online", fontSize = 12.sp, color = BentoPrimary)
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (currentLyrics?.source == "loading") {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = BentoPrimary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Fetching Lyrics...",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(38.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No lyrics available for this track.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDarkTheme) BentoTextSecondary else Color(0xFF475569),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onForceFetchLyrics,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isDarkTheme) BentoCardDarkPurple else Color(0xFFF1F5F9)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isDarkTheme) Color.Transparent else Color(0xFFCBD5E1))
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = if (isDarkTheme) BentoTextPrimary else Color(0xFF1E293B), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Auto Fetch", fontSize = 12.sp, color = if (isDarkTheme) BentoTextPrimary else Color(0xFF1E293B))
                                }
                                Button(
                                    onClick = onPickLrcFile,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isDarkTheme) BentoCardDarkPurple else Color(0xFFF1F5F9)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isDarkTheme) Color.Transparent else Color(0xFFCBD5E1))
                                ) {
                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = if (isDarkTheme) BentoTextPrimary else Color(0xFF1E293B), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Local .LRC", fontSize = 12.sp, color = if (isDarkTheme) BentoTextPrimary else Color(0xFF1E293B))
                                }
                                Button(
                                    onClick = onOpenSearchLyrics,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isDarkTheme) BentoCardDarkPurple else Color(0xFFF1F5F9)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isDarkTheme) Color.Transparent else Color(0xFFCBD5E1))
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = if (isDarkTheme) BentoTextPrimary else Color(0xFF1E293B), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Search", fontSize = 12.sp, color = if (isDarkTheme) BentoTextPrimary else Color(0xFF1E293B))
                                }
                            }
                            if (isTranscribeTranslateEnabled) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        transcribeDialogInitialMode = AudioTranscribeMode.TRANSCRIBE
                                        showTranscribeDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.22f)),
                                    border = BorderStroke(1.dp, Color(0xFF00E5FF))
                                ) {
                                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Transcribe Audio (🎼)", fontSize = 12.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } // Close else
        } // Close main Column

        // Language & Operation Selection Dialog
        if (showTranscribeDialog) {
            val configuredLang = TranscribeLanguage.fromCode(audioTranscribeTargetLang)
            val configuredMode = AudioTranscribeMode.fromId(audioTranscribeMode)
            val hasExistingLyrics = syncedLines.isNotEmpty() || !currentLyrics?.plainLyrics.isNullOrBlank()

            TranscribeLanguageDialog(
                song = song,
                initialMode = if (configuredMode != AudioTranscribeMode.OFF) configuredMode else transcribeDialogInitialMode,
                initialLanguage = configuredLang,
                hasExistingLyrics = hasExistingLyrics,
                onDismiss = { showTranscribeDialog = false },
                onStartOperation = { mode, language ->
                    showTranscribeDialog = false
                    if (mode == AudioTranscribeMode.TRANSCRIBE) {
                        onTranscribeAudio(song, language)
                    } else if (mode == AudioTranscribeMode.TRANSLATE) {
                        onTranslateLyrics(song, language)
                    }
                }
            )
        }

        // Preview Transcribed / Translated Lyrics Dialog
        if (transcribeLyricsPreview != null) {
            TranscribePreviewDialog(
                preview = transcribeLyricsPreview,
                onSave = { editedPreview -> onSaveTranscribedLyrics(editedPreview) },
                onDiscard = onDiscardTranscribedLyrics
            )
        }

        // Lyrics Offset Adjustment Dialog
        if (showOffsetDialog) {
            LyricsOffsetDialog(
                song = song,
                currentOffsetMs = syncOffsetMs,
                onOffsetChanged = { newOffset ->
                    syncOffsetMs = newOffset
                    onSaveLyricsOffset?.invoke(song.id, newOffset)
                },
                onDismiss = { showOffsetDialog = false }
            )
        }

        // Top punch-hole camera black cover + smooth gradient transition for full-screen view (only when lyrics view is closed so it does not interrupt lyrics text)
        if (!showLyricsView) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(Color.Black)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black,
                                0.45f to Color.Black.copy(alpha = 0.58f),
                                1.0f to Color.Transparent
                            )
                        )
                )
            }
        }
    }
}
