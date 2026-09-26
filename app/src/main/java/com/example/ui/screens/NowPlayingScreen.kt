package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.Player
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
    modifier: Modifier = Modifier
) {
    val livePositionMs: Long = if (playbackPositionFlow != null) playbackPositionFlow.collectAsState().value else playbackState.positionMs
    val song = playbackState.currentSong
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

        AlertDialog(
            onDismissRequest = { showSearchLyricsDialog = false },
            containerColor = BentoCardSurface,
            title = {
                Text("Find Lyrics Online", color = BentoTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                        label = { Text("Song Title / Artist", color = BentoTextSecondary) },
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
                                        Icon(Icons.Default.Close, contentDescription = "Clear text", tint = BentoTextSecondary)
                                    }
                                }
                                IconButton(onClick = { onSearchLyricsCandidates(searchQueryInput, true) }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = BentoPrimary)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = BentoTextPrimary,
                            unfocusedTextColor = BentoTextPrimary,
                            focusedBorderColor = BentoPrimary,
                            unfocusedBorderColor = BentoBorder,
                            focusedContainerColor = BentoCardSurface,
                            unfocusedContainerColor = BentoCardSurface,
                            cursorColor = BentoPrimary
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
                                color = Color.White.copy(alpha = 0.6f),
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
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onApplyLyricsCandidate(candidate)
                                            showSearchLyricsDialog = false
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
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
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            if (candidate.isSynced) {
                                                Surface(
                                                    color = BentoPrimary.copy(alpha = 0.2f),
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
                                                    color = Color.White.copy(alpha = 0.12f),
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
                                                            tint = Color.White.copy(alpha = 0.7f),
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Text(
                                                            text = "Plain Text",
                                                            fontSize = 11.sp,
                                                            color = Color.White.copy(alpha = 0.85f),
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${candidate.artistName}${if (candidate.albumName.isNotBlank()) " • " + candidate.albumName else ""}",
                                            color = Color.White.copy(alpha = 0.7f),
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

    // Intercept back button when lyrics, queue, or dialogs are open
    androidx.activity.compose.BackHandler(enabled = showLyricsView || showQueueSheet || showTimerDialog || showOptionsMenu || showSearchLyricsDialog) {
        when {
            showSearchLyricsDialog -> showSearchLyricsDialog = false
            showLyricsView -> showLyricsView = false
            showQueueSheet -> showQueueSheet = false
            showTimerDialog -> showTimerDialog = false
            showOptionsMenu -> showOptionsMenu = false
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val lrcPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
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
            if (!fileName.lowercase().endsWith(".lrc") && !fileName.lowercase().endsWith(".txt")) {
                android.widget.Toast.makeText(context, "Invalid file format. Please select a .lrc lyrics file.", android.widget.Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
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

    val formattedElapsed = remember(livePositionMs) {
        val totalSec = livePositionMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        String.format("%d:%02d", min, sec)
    }

    val effectiveDurationMs = if (playbackState.durationMs > 0L) playbackState.durationMs else (playbackState.currentSong?.durationMs ?: 0L)
    val formattedDuration = remember(effectiveDurationMs) {
        val totalSec = effectiveDurationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        String.format("%d:%02d", min, sec)
    }

    val currentBg = BentoBackground
    val ambientThemeColor = dynamicArtworkColor ?: BentoPrimary
    val bgBrush = remember(ambientThemeColor, currentBg) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                ambientThemeColor.copy(alpha = 0.45f),
                ambientThemeColor.copy(alpha = 0.18f),
                currentBg
            )
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

                            Box {
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

                                DropdownMenu(
                                    expanded = showOptionsMenu,
                                    onDismissRequest = { showOptionsMenu = false },
                                    modifier = Modifier.background(BentoCardSurface)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Sleep Timer", color = BentoTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null, tint = BentoPrimary) },
                                        onClick = {
                                            showOptionsMenu = false
                                            showTimerDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to Playlist", color = BentoTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = BentoPrimary) },
                                        onClick = {
                                            showOptionsMenu = false
                                            onAddToPlaylist(song)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Play Next", color = BentoTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.FormatListNumberedRtl, contentDescription = null, tint = BentoPrimary) },
                                        onClick = {
                                            showOptionsMenu = false
                                            onPlayNext(song)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Remove from Queue", color = BentoTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.RemoveFromQueue, contentDescription = null, tint = BentoPrimary) },
                                        onClick = {
                                            showOptionsMenu = false
                                            onRemoveFromQueue(playbackState.queueIndex)
                                        }
                                    )
                                    if (onEditTags != null) {
                                        DropdownMenuItem(
                                            text = { Text("Edit Audio Tags & Lyrics", color = BentoTextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.EditNote, contentDescription = null, tint = BentoPrimary) },
                                            onClick = {
                                                showOptionsMenu = false
                                                onEditTags(song)
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Delete Song", color = BentoAccentPinkText) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = BentoAccentPinkText) },
                                        onClick = {
                                            showOptionsMenu = false
                                            showDeleteConfirmDialog = true
                                        }
                                    )
                                }
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
                            progressMs = livePositionMs,
                            durationMs = playbackState.durationMs,
                            isPlaying = playbackState.isPlaying,
                            style = waveformStyle,
                            accentColor = BentoPrimary,
                            onSeekTo = onSeekTo,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = formattedElapsed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSecondary)
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(BentoCardSurface.copy(alpha = 0.95f))
                        .border(1.dp, BentoBorder.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
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
                        onPickLrcFile = { lrcPickerLauncher.launch(arrayOf("text/plain", "application/x-lrc", "text/lrc", "text/x-lrc", "application/octet-stream")) },
                        onCloseLyrics = { showLyricsView = false },
                        coroutineScope = coroutineScope
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
                com.example.ui.theme.NowPlayingLayout.MODERN_BENTO -> {
                    // Standard Portrait View
                    Box(modifier = Modifier.fillMaxSize()) {
                        com.example.ui.components.AlbumArtGestureBox(
                            enabled = gesturePlaybackEnabled,
                            isPlaying = playbackState.isPlaying,
                            onTogglePlayPause = onTogglePlayPause,
                            onSkipPrevious = onSkipPrevious,
                            onSkipNext = onSkipNext,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.72f)
                        ) {
                            AlbumArtView(
                                song = song,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 0
                            )
                        }

                // Top dismiss / collapse swipe down button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                        .clip(CircleShape)
                        .background(BentoCardSurface.copy(alpha = 0.7f))
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = BentoTextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Dark Gradient Scrim overlay over lower screen
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    BentoBackground.copy(alpha = 0.5f),
                                    BentoBackground.copy(alpha = 0.95f),
                                    BentoBackground
                                )
                            )
                        )
                )
            }

            // Lower Controls & Song Info Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                // Song Title & Artist + Favorite & Options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.displayTitle,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${song.displayArtist} • ${song.displayAlbum}",
                            fontSize = 14.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Options Button
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(BentoCardPurple)
                                    .clickable { showOptionsMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = BentoTextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showOptionsMenu,
                                onDismissRequest = { showOptionsMenu = false },
                                modifier = Modifier.background(BentoCardSurface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Choose .LRC File", color = BentoTextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = BentoPrimary) },
                                    onClick = {
                                        showOptionsMenu = false
                                        lrcPickerLauncher.launch(arrayOf("text/plain", "application/x-lrc", "text/lrc", "text/x-lrc", "application/octet-stream"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Up Next Queue", color = BentoTextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null, tint = BentoPrimary) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showQueueSheet = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to Playlist", color = BentoTextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = BentoPrimary) },
                                    onClick = {
                                        showOptionsMenu = false
                                        onAddToPlaylist(song)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Play Next", color = BentoTextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.FormatListNumberedRtl, contentDescription = null, tint = BentoPrimary) },
                                    onClick = {
                                        showOptionsMenu = false
                                        onPlayNext(song)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove from Queue", color = BentoTextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.RemoveFromQueue, contentDescription = null, tint = BentoPrimary) },
                                    onClick = {
                                        showOptionsMenu = false
                                        onRemoveFromQueue(playbackState.queueIndex)
                                    }
                                )
                                if (onEditTags != null) {
                                    DropdownMenuItem(
                                        text = { Text("Edit Audio Tags & Lyrics", color = BentoTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.EditNote, contentDescription = null, tint = BentoPrimary) },
                                        onClick = {
                                            showOptionsMenu = false
                                            onEditTags(song)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete Song", color = BentoAccentPinkText) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = BentoAccentPinkText) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showDeleteConfirmDialog = true
                                    }
                                )
                            }
                        }

                        // Favorite Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BentoCardPurple)
                                .clickable { onFavoriteToggle() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (song.isFavorite) BentoAccentPinkText else BentoTextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Waveform & Progress Visualization
                Column(modifier = Modifier.fillMaxWidth()) {
                    com.example.ui.components.NowPlayingWaveformVisualizer(
                        progressMs = livePositionMs,
                        durationMs = playbackState.durationMs,
                        isPlaying = playbackState.isPlaying,
                        style = waveformStyle,
                        accentColor = BentoPrimary,
                        onSeekTo = onSeekTo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = formattedElapsed, fontSize = 12.sp, color = BentoTextSecondary)
                        Text(text = formattedDuration, fontSize = 12.sp, color = BentoTextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Large White Playback Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous Button
                    IconButton(
                        onClick = {
                            com.example.util.HapticUtils.performClickHaptic(context)
                            onSkipPrevious()
                        },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(42.dp)
                        )
                    }

                    // Play / Pause Pill Button
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(52.dp)
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
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Next Button
                    IconButton(
                        onClick = {
                            com.example.util.HapticUtils.performClickHaptic(context)
                            onSkipNext()
                        },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))



                // Audio Device Route Interactive Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            showAudioOutputSheet = true
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = BentoCardSurface.copy(alpha = 0.7f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
                        modifier = Modifier.testTag("audio_output_route_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = audioRouteInfo.icon,
                                contentDescription = "Audio Output Device",
                                tint = BentoPrimary.copy(alpha = 0.95f),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = audioRouteInfo.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = BentoTextPrimary,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = "Switch Output Device",
                                tint = BentoTextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Bottom Quick Actions Bar (Shuffle, Repeat, Queue, Timer, Lyrics)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        HapticUtils.performClickHaptic(context)
                        onToggleShuffle()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (playbackState.isShuffleEnabled) BentoPrimary else BentoTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(onClick = {
                        HapticUtils.performClickHaptic(context)
                        onToggleRepeat()
                    }) {
                        Icon(
                            imageVector = if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) BentoPrimary else BentoTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(onClick = {
                        HapticUtils.performClickHaptic(context)
                        showQueueSheet = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Up Next Queue",
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(onClick = {
                        HapticUtils.performClickHaptic(context)
                        showTimerDialog = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Sleep Timer",
                            tint = if (sleepTimerState.isActive) BentoPrimary else BentoTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Lyrics Toggle Button with Swipe Hint
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (showLyricsView) BentoPrimary.copy(alpha = 0.35f) else BentoCardPurple)
                            .clickable {
                                HapticUtils.performClickHaptic(context)
                                showLyricsView = !showLyricsView
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = "Lyrics",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Lyrics",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                    }
                }
            }
        }
    }
}

        if (currentLayout != com.example.ui.theme.NowPlayingLayout.MODERN_BENTO && !isLandscape) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 10.dp, end = 50.dp)
            ) {
                DropdownMenu(
                    expanded = showOptionsMenu,
                    onDismissRequest = { showOptionsMenu = false },
                    modifier = Modifier.background(BentoCardSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Choose .LRC File", color = BentoTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = BentoPrimary) },
                        onClick = {
                            showOptionsMenu = false
                            lrcPickerLauncher.launch(arrayOf("text/plain", "application/x-lrc", "text/lrc", "text/x-lrc", "application/octet-stream"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Search Online Lyrics", color = BentoTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.ManageSearch, contentDescription = null, tint = BentoPrimary) },
                        onClick = {
                            showOptionsMenu = false
                            showSearchLyricsDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Up Next Queue", color = BentoTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null, tint = BentoPrimary) },
                        onClick = {
                            showOptionsMenu = false
                            showQueueSheet = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Sleep Timer", color = BentoTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Bedtime, contentDescription = null, tint = BentoPrimary) },
                        onClick = {
                            showOptionsMenu = false
                            showTimerDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Playlist", color = BentoTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = BentoPrimary) },
                        onClick = {
                            showOptionsMenu = false
                            onAddToPlaylist(song)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Play Next", color = BentoTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.FormatListNumberedRtl, contentDescription = null, tint = BentoPrimary) },
                        onClick = {
                            showOptionsMenu = false
                            onPlayNext(song)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from Queue", color = BentoTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.RemoveFromQueue, contentDescription = null, tint = BentoPrimary) },
                        onClick = {
                            showOptionsMenu = false
                            onRemoveFromQueue(playbackState.queueIndex)
                        }
                    )
                    if (onEditTags != null) {
                        DropdownMenuItem(
                            text = { Text("Edit Audio Tags & Lyrics", color = BentoTextPrimary) },
                            leadingIcon = { Icon(Icons.Default.EditNote, contentDescription = null, tint = BentoPrimary) },
                            onClick = {
                                showOptionsMenu = false
                                onEditTags(song)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete Song", color = BentoAccentPinkText) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = BentoAccentPinkText) },
                        onClick = {
                            showOptionsMenu = false
                            showDeleteConfirmDialog = true
                        }
                    )
                }
            }
        }

    // Spotify-style Swipe-up Full Overlay Lyrics Panel (Works across all UI Themes & Layouts)
    AnimatedVisibility(
        visible = showLyricsView,
        enter = slideInVertically { it },
        exit = slideOutVertically { it }
    ) {
        val lyricsBg = BentoBackground.copy(alpha = 0.97f)

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
                onPickLrcFile = { lrcPickerLauncher.launch(arrayOf("text/plain", "application/x-lrc", "text/lrc", "text/x-lrc", "application/octet-stream")) },
                onCloseLyrics = { showLyricsView = false },
                coroutineScope = coroutineScope
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
    }
}

@Composable
private fun LyricsPanelContent(
    song: Song,
    currentLyrics: Lyrics?,
    playbackState: PlaybackState,
    positionMs: Long = playbackState.positionMs,
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
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current
    var syncOffsetMs by remember(song.id) {
        mutableLongStateOf(onGetLyricsOffset?.invoke(song.id) ?: 0L)
    }
    var showSyncOffsetDialog by remember { mutableStateOf(false) }

    val effectivePositionMs = (positionMs + syncOffsetMs).coerceAtLeast(0L)
    val syncedLines = remember(currentLyrics) {
        try {
            currentLyrics?.parseSyncedLines() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                            color = BentoTextPrimary
                        )
                        if (syncedLines.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .background(BentoPrimary.copy(alpha = 0.2f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("SYNCED", fontSize = 10.sp, color = BentoPrimary, fontWeight = FontWeight.Bold)
                            }
                        } else if (!currentLyrics?.plainLyrics.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .background(BentoCardPurple, CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("PLAIN TEXT", fontSize = 10.sp, color = BentoTextSecondary, fontWeight = FontWeight.Medium)
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

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onOpenSearchLyrics, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Search, contentDescription = "Find Lyrics", tint = BentoPrimary, modifier = Modifier.size(20.dp))
                    }

                    if (isLandscape) {
                        IconButton(onClick = onForceFetchLyrics, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Auto Fetch", tint = BentoPrimary, modifier = Modifier.size(20.dp))
                        }
                    }

                    if (syncedLines.isNotEmpty()) {
                        TextButton(
                            onClick = { showSyncOffsetDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (syncOffsetMs != 0L) BentoPrimary else BentoTextSecondary
                            )
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (syncOffsetMs == 0L) "Sync Offset" else "${if (syncOffsetMs > 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", syncOffsetMs / 1000f)}s",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
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
                                .background(BentoCardPurple)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close Lyrics",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isCurrentLine) ambientThemeColor.copy(alpha = 0.32f) else Color.Transparent
                                    )
                                    .border(
                                        width = if (isCurrentLine) 1.5.dp else 0.dp,
                                        color = if (isCurrentLine) ambientThemeColor.copy(alpha = 0.65f) else Color.Transparent,
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
                                Text(
                                    text = lineText,
                                    fontSize = if (isCurrentLine) (if (isLandscape) 19.sp else 22.sp) else (if (isLandscape) 14.sp else 15.sp),
                                    fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isCurrentLine) BentoTextPrimary else BentoTextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            scaleX = lineScale
                                            scaleY = lineScale
                                            alpha = if (isCurrentLine) 1.0f else 0.70f
                                        }
                                )
                            }
                        }
                    }

                    // Sync button & Sync Offset pill at bottom-left:
                    // The Sync button jumps to the offset-adjusted current line (activeIndex).
                    // If an offset is configured, show the offset pill and the Sync button when applicable.
                    val isOffsetDurationSet = syncOffsetMs != 0L
                    androidx.compose.animation.AnimatedVisibility(
                        visible = shouldShowSyncButton || isOffsetDurationSet,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (shouldShowSyncButton) {
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
                            }

                            Surface(
                                onClick = { showSyncOffsetDialog = true },
                                color = BentoCardSurface.copy(alpha = 0.95f),
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.6f)),
                                shadowElevation = 4.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Adjust Sync Offset",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (syncOffsetMs != 0L) BentoPrimary else BentoTextSecondary
                                    )
                                    val offsetLabel = if (syncOffsetMs == 0L) "Offset" else "${if (syncOffsetMs > 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", syncOffsetMs / 1000f)}s"
                                    Text(
                                        text = offsetLabel,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (syncOffsetMs != 0L) BentoPrimary else BentoTextSecondary
                                    )
                                }
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = lineText,
                                    fontSize = if (isLandscape) 15.sp else 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BentoTextPrimary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onPickLrcFile,
                                border = androidx.compose.foundation.BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary)
                            ) {
                                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = BentoPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Change or Pick .LRC File", fontSize = 12.sp, color = BentoPrimary)
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
                                color = BentoTextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onForceFetchLyrics,
                                    colors = ButtonDefaults.buttonColors(containerColor = BentoCardDarkPurple)
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = BentoTextPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Auto Fetch", fontSize = 12.sp, color = BentoTextPrimary)
                                }
                                Button(
                                    onClick = onPickLrcFile,
                                    colors = ButtonDefaults.buttonColors(containerColor = BentoCardDarkPurple)
                                ) {
                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = BentoTextPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Local .LRC", fontSize = 12.sp, color = BentoTextPrimary)
                                }
                            }
                        }
                    }
                }
            } // Close else
        } // Close main Column

        // Bottom Right Floating "Find Lyrics" button (only in portrait mode since header has it in landscape)
        if (!isLandscape) {
            SmallFloatingActionButton(
                onClick = onOpenSearchLyrics,
                containerColor = BentoPrimary,
                contentColor = BentoOnPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 12.dp, end = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Find Lyrics", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Search Lyrics", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Lyrics Sync Offset Adjustment Dialog
        if (showSyncOffsetDialog) {
            AlertDialog(
                onDismissRequest = { showSyncOffsetDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = {
                    Text(
                        text = "Lyrics Sync Offset",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = BentoTextPrimary
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = song.displayTitle,
                            fontSize = 13.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Current offset value display
                        Surface(
                            color = if (syncOffsetMs != 0L) BentoPrimary.copy(alpha = 0.15f) else BentoCardPurple,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (syncOffsetMs != 0L) BentoPrimary.copy(alpha = 0.4f) else BentoBorder
                            ),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = String.format(java.util.Locale.US, "%+.2fs", syncOffsetMs / 1000f),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (syncOffsetMs != 0L) BentoPrimary else BentoTextPrimary
                                )
                                Text(
                                    text = when {
                                        syncOffsetMs > 0 -> "Lyrics delayed by ${String.format(java.util.Locale.US, "%.2f", syncOffsetMs / 1000f)}s (shows later)"
                                        syncOffsetMs < 0 -> "Lyrics advanced by ${String.format(java.util.Locale.US, "%.2f", (-syncOffsetMs) / 1000f)}s (shows earlier)"
                                        else -> "Default timing (0.00s)"
                                    },
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }

                        // Quick Step Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(-500L to "-0.5s", -100L to "-0.1s", 0L to "Reset", 100L to "+0.1s", 500L to "+0.5s").forEach { (delta, label) ->
                                TextButton(
                                    onClick = {
                                        val newOffset = if (delta == 0L) 0L else (syncOffsetMs + delta).coerceIn(-10000L, 10000L)
                                        syncOffsetMs = newOffset
                                        onSaveLyricsOffset?.invoke(song.id, newOffset)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = if (delta == 0L) Color(0xFFFF5252) else BentoPrimary
                                    )
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Continuous Slider (-5s to +5s)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Slider(
                                value = (syncOffsetMs / 100f).coerceIn(-50f, 50f),
                                onValueChange = { sliderVal ->
                                    val newOffset = (sliderVal * 100f).toLong()
                                    syncOffsetMs = newOffset
                                    onSaveLyricsOffset?.invoke(song.id, newOffset)
                                },
                                valueRange = -50f..50f,
                                steps = 99,
                                colors = SliderDefaults.colors(
                                    thumbColor = BentoPrimary,
                                    activeTrackColor = BentoPrimary,
                                    inactiveTrackColor = BentoBorder
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("-5.0s (Earlier)", fontSize = 10.sp, color = BentoTextSecondary)
                                Text("+5.0s (Later)", fontSize = 10.sp, color = BentoTextSecondary)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onSaveLyricsOffset?.invoke(song.id, syncOffsetMs)
                            showSyncOffsetDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                    ) {
                        Text("Done", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = BentoCardSurface,
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}
