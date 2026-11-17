package com.example.ui.screens.layouts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.TranscribeLanguage
import com.example.service.transcription.TranscribeLyricsPreview
import com.example.ui.components.AlbumArtView
import com.example.ui.components.LyricsOffsetDialog
import com.example.ui.components.TranscribeLanguageDialog
import com.example.ui.components.TranscribePreviewDialog
import com.example.ui.screens.cleanLyricDisplayText
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Shared styled implementation for the 3 alternate Lyrics UI layouts:
 * - FLUID_GLOW ("Fluid Glow"): Editorial large left-aligned lyrics with mini artwork header & Play/Pause orb
 * - KARAOKE_SPOTLIGHT ("Karaoke Stage"): Spotlight active verse with live progress sweep & NEXT UP cue
 * - EDITORIAL_TIMELINE ("Studio Timeline"): Monospaced vertical timestamp rail with luminous node markers
 *
 * Keeps CLASSIC_DEFAULT completely untouched in NowPlayingScreen.kt.
 * All variants provide full access to Search Lyrics, Offset, Transcribe/Translate, .LRC File, Sync, and Seek.
 */
@Composable
fun AlternateLyricsPanel(
    lyricsLayout: LyricsLayout,
    song: Song,
    currentLyrics: Lyrics?,
    playbackState: PlaybackState,
    positionMs: Long = playbackState.positionMs,
    playbackPositionFlow: StateFlow<Long>? = null,
    showLyricsView: Boolean,
    userIsScrollingLyrics: Boolean,
    isLandscape: Boolean = false,
    lyricsAutoScroll: Boolean = true,
    onSaveLyricsOffset: ((Long, Long) -> Unit)? = null,
    onGetLyricsOffset: ((Long) -> Long)? = null,
    lyricsSyncOffsetMs: Long? = null,
    onFlushLyricsOffset: ((Long) -> Unit)? = null,
    ambientThemeColor: Color = BentoPrimary,
    onUserIsScrollingChange: (Boolean) -> Unit,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit = {},
    onForceFetchLyrics: () -> Unit,
    onOpenSearchLyrics: () -> Unit = {},
    onPickLrcFile: () -> Unit,
    onCloseLyrics: () -> Unit,
    coroutineScope: CoroutineScope,
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
    onRetranscribedLyrics: (TranscribeLyricsPreview) -> Unit = {},
    onTranslateLyrics: (Song, TranscribeLanguage) -> Unit = { _, _ -> },
    onTranscribeAudio: (Song, TranscribeLanguage) -> Unit = { _, _ -> },
    onUndoLyricsChanges: (Long) -> Unit = {},
    onClearTranscribeError: () -> Unit = {}
) {
    val livePositionState = playbackPositionFlow?.collectAsStateWithLifecycle(initialValue = positionMs)
    val context = LocalContext.current

    var syncOffsetMs by remember(song.id) {
        mutableLongStateOf(lyricsSyncOffsetMs ?: onGetLyricsOffset?.invoke(song.id) ?: 0L)
    }
    LaunchedEffect(song.id, lyricsSyncOffsetMs, currentLyrics) {
        syncOffsetMs = lyricsSyncOffsetMs ?: onGetLyricsOffset?.invoke(song.id) ?: 0L
    }
    var showOffsetDialog by remember { mutableStateOf(false) }
    var showTranscribeDialog by remember { mutableStateOf(false) }
    var transcribeDialogInitialMode by remember { mutableStateOf(AudioTranscribeMode.TRANSLATE) }

    val syncedLines = remember(currentLyrics) {
        try {
            currentLyrics?.parseSyncedLines() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
    val hasSynced = syncedLines.isNotEmpty()
    val hasPlain = !currentLyrics?.plainLyrics.isNullOrBlank()
    val hasLyrics = hasSynced || hasPlain

    val glassConfig = LocalGlassThemeConfig.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val primaryInk = if (isDarkTheme) Color.White else Color(0xFF0F172A)
    val secondaryInk = if (isDarkTheme) Color.White.copy(alpha = 0.68f) else Color(0xFF475569)
    val surfaceBg = if (isDarkTheme) Color(0xFF13141F) else Color(0xFFF1F5F9)
    val borderStrokeColor = if (isDarkTheme) Color.White.copy(alpha = 0.14f) else Color(0xFFCBD5E1)

    val offsetChipText = remember(syncOffsetMs) {
        if (syncOffsetMs == 0L) "Offset"
        else {
            val sec = syncOffsetMs / 1000.0
            if (syncOffsetMs > 0) "+${"%.1f".format(sec)}s" else "${"%.1f".format(sec)}s"
        }
    }

    fun formatMsTimestamp(ms: Long): String {
        val totalSecs = (ms / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(totalSecs / 60L, totalSecs % 60L)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (glassConfig.enabled) Color.Transparent else if (isDarkTheme) Color(0xFF09090E) else Color.White)
            .testTag("lyrics_panel_${lyricsLayout.id.lowercase()}")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------------------------------------------------------
            // VARIANT HEADER BAR
            // ---------------------------------------------------------
            when (lyricsLayout) {
                LyricsLayout.FLUID_GLOW -> {
                    // Editorial Mini-Player Artwork + Title + Play/Pause Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AlbumArtView(
                            song = song,
                            modifier = Modifier
                                .size(if (isLandscape) 40.dp else 48.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            cornerRadius = 10
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.displayTitle,
                                fontSize = if (isLandscape) 15.sp else 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.displayArtist,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = secondaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onTogglePlayPause()
                            },
                            shape = CircleShape,
                            color = ambientThemeColor.copy(alpha = 0.22f),
                            border = BorderStroke(1.dp, ambientThemeColor.copy(alpha = 0.55f)),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play or Pause",
                                    tint = primaryInk,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (!isLandscape) {
                            IconButton(
                                onClick = onCloseLyrics,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(surfaceBg)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Close Lyrics",
                                    tint = primaryInk,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Sleek Fluid Action Capsule Bar (Search, Offset, Transcribe/Translate, .LRC)
                    LyricsActionToolbarStrip(
                        hasSynced = hasSynced,
                        hasLyrics = hasLyrics,
                        syncOffsetMs = syncOffsetMs,
                        offsetText = offsetChipText,
                        isTranscribeTranslateEnabled = isTranscribeTranslateEnabled,
                        accentColor = ambientThemeColor,
                        primaryInk = primaryInk,
                        surfaceBg = surfaceBg,
                        borderStrokeColor = borderStrokeColor,
                        onOpenSearch = onOpenSearchLyrics,
                        onOpenOffset = { showOffsetDialog = true },
                        onOpenTranscribe = {
                            transcribeDialogInitialMode = if (hasLyrics) AudioTranscribeMode.TRANSLATE else AudioTranscribeMode.TRANSCRIBE
                            showTranscribeDialog = true
                        },
                        onPickLrcFile = onPickLrcFile
                    )
                }

                LyricsLayout.KARAOKE_SPOTLIGHT -> {
                    // Stage Spotlight Header with Live Badge + Tools
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ambientThemeColor.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, ambientThemeColor.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = null, tint = ambientThemeColor, modifier = Modifier.size(13.dp))
                                    Text(
                                        text = "KARAOKE STAGE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ambientThemeColor,
                                        letterSpacing = 0.8.sp
                                    )
                                }
                            }
                            Text(
                                text = song.displayTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = primaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onTogglePlayPause()
                                },
                                shape = CircleShape,
                                color = ambientThemeColor,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = if (ambientThemeColor.luminance() > 0.5f) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (!isLandscape) {
                                IconButton(
                                    onClick = onCloseLyrics,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(surfaceBg)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close Lyrics", tint = primaryInk, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LyricsActionToolbarStrip(
                        hasSynced = hasSynced,
                        hasLyrics = hasLyrics,
                        syncOffsetMs = syncOffsetMs,
                        offsetText = offsetChipText,
                        isTranscribeTranslateEnabled = isTranscribeTranslateEnabled,
                        accentColor = ambientThemeColor,
                        primaryInk = primaryInk,
                        surfaceBg = surfaceBg,
                        borderStrokeColor = borderStrokeColor,
                        onOpenSearch = onOpenSearchLyrics,
                        onOpenOffset = { showOffsetDialog = true },
                        onOpenTranscribe = {
                            transcribeDialogInitialMode = if (hasLyrics) AudioTranscribeMode.TRANSLATE else AudioTranscribeMode.TRANSCRIBE
                            showTranscribeDialog = true
                        },
                        onPickLrcFile = onPickLrcFile
                    )
                }

                LyricsLayout.EDITORIAL_TIMELINE -> {
                    // Studio Telemetry Monospace Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (hasSynced) ambientThemeColor else secondaryInk)
                                )
                                Text(
                                    text = "TIMELINE STUDIO",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = ambientThemeColor,
                                    letterSpacing = 1.1.sp
                                )
                            }
                            Text(
                                text = "${song.displayTitle} — ${song.displayArtist}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = primaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!isLandscape) {
                            IconButton(
                                onClick = onCloseLyrics,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(surfaceBg)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close Lyrics", tint = primaryInk, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LyricsActionToolbarStrip(
                        hasSynced = hasSynced,
                        hasLyrics = hasLyrics,
                        syncOffsetMs = syncOffsetMs,
                        offsetText = offsetChipText,
                        isTranscribeTranslateEnabled = isTranscribeTranslateEnabled,
                        accentColor = ambientThemeColor,
                        primaryInk = primaryInk,
                        surfaceBg = surfaceBg,
                        borderStrokeColor = borderStrokeColor,
                        onOpenSearch = onOpenSearchLyrics,
                        onOpenOffset = { showOffsetDialog = true },
                        onOpenTranscribe = {
                            transcribeDialogInitialMode = if (hasLyrics) AudioTranscribeMode.TRANSLATE else AudioTranscribeMode.TRANSCRIBE
                            showTranscribeDialog = true
                        },
                        onPickLrcFile = onPickLrcFile
                    )
                }

                LyricsLayout.CLASSIC_DEFAULT -> {
                    // Unreachable: handled by untouched LyricsPanelContent
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
                    color = BentoPrimary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = BentoPrimary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text(
                            text = transcribeStatusMessage ?: "Processing lyrics...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = primaryInk,
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
                            color = primaryInk,
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
                            Text("Retry", fontSize = 11.sp, color = BentoPrimary, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = onClearTranscribeError, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = secondaryInk, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // ---------------------------------------------------------
            // LYRICS BODY
            // ---------------------------------------------------------
            if (hasSynced) {
                val listState = rememberLazyListState()
                var userHasManuallyScrolled by remember(song.id) { mutableStateOf(false) }
                var lastAppliedOffsetMs by remember(song.id) { mutableLongStateOf(syncOffsetMs) }
                val isUserDragging by listState.interactionSource.collectIsDraggedAsState()

                val rawPosNow = livePositionState?.value?.let { if (it > 0L || positionMs == 0L) it else positionMs } ?: positionMs
                val effectivePosNow = rawPosNow + syncOffsetMs

                val highlightedIndex by remember(syncedLines, syncOffsetMs, positionMs, livePositionState) {
                    derivedStateOf {
                        if (syncedLines.isEmpty()) return@derivedStateOf -1
                        val liveVal = livePositionState?.value
                        val rawPos = if (liveVal != null && (liveVal > 0L || positionMs == 0L)) liveVal else positionMs
                        val effectivePos = rawPos + syncOffsetMs
                        if (effectivePos < syncedLines[0].first) -1
                        else Lyrics.findActiveLineIndex(syncedLines, effectivePos)
                    }
                }
                val scrollIndex by remember {
                    derivedStateOf {
                        if (syncedLines.isEmpty()) 0 else highlightedIndex.coerceAtLeast(0)
                    }
                }
                val manuallyViewedIndex by remember {
                    derivedStateOf {
                        val layoutInfo = listState.layoutInfo
                        val visible = layoutInfo.visibleItemsInfo
                        if (visible.isEmpty()) scrollIndex
                        else {
                            val center = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                            visible.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - center) }?.index ?: scrollIndex
                        }
                    }
                }

                LaunchedEffect(isUserDragging) {
                    if (isUserDragging) {
                        userHasManuallyScrolled = true
                        onUserIsScrollingChange(true)
                    }
                }
                LaunchedEffect(song.id, showLyricsView) {
                    if (showLyricsView) {
                        userHasManuallyScrolled = false
                        onUserIsScrollingChange(false)
                        if (lyricsAutoScroll && scrollIndex in syncedLines.indices) {
                            listState.animateScrollToItem(scrollIndex)
                        }
                    }
                }
                LaunchedEffect(syncOffsetMs) {
                    if (syncOffsetMs != lastAppliedOffsetMs) {
                        lastAppliedOffsetMs = syncOffsetMs
                        userHasManuallyScrolled = false
                        onUserIsScrollingChange(false)
                        if (!lyricsAutoScroll && scrollIndex in syncedLines.indices) {
                            listState.animateScrollToItem(scrollIndex)
                        }
                    }
                }
                LaunchedEffect(scrollIndex, userHasManuallyScrolled, lyricsAutoScroll) {
                    if (lyricsAutoScroll && !userHasManuallyScrolled && scrollIndex in syncedLines.indices) {
                        listState.animateScrollToItem(scrollIndex)
                    }
                }

                val latestSyncOffsetMs by rememberUpdatedState(syncOffsetMs)
                val latestSyncedLines by rememberUpdatedState(syncedLines)
                val latestAutoScroll by rememberUpdatedState(lyricsAutoScroll)
                LaunchedEffect(song.id, playbackPositionFlow) {
                    var lastRawPos = playbackPositionFlow?.value ?: positionMs
                    if (playbackPositionFlow != null) {
                        playbackPositionFlow.collect { rawPos ->
                            if (kotlin.math.abs(rawPos - lastRawPos) > 2500L) {
                                userHasManuallyScrolled = false
                                onUserIsScrollingChange(false)
                                val effPos = rawPos + latestSyncOffsetMs
                                val idx = Lyrics.findActiveLineIndex(latestSyncedLines, effPos).coerceAtLeast(0)
                                if (latestAutoScroll && idx in latestSyncedLines.indices) {
                                    listState.animateScrollToItem(idx)
                                }
                            }
                            lastRawPos = rawPos
                        }
                    }
                }

                val shouldShowSyncButton by remember {
                    derivedStateOf {
                        if (lyricsAutoScroll) userHasManuallyScrolled
                        else manuallyViewedIndex != scrollIndex
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val halfHeight = maxHeight / 2
                    val verticalPad = (halfHeight - if (isLandscape) 36.dp else 44.dp).coerceAtLeast(36.dp)

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
                        contentPadding = PaddingValues(top = verticalPad, bottom = verticalPad),
                        verticalArrangement = Arrangement.spacedBy(
                            when (lyricsLayout) {
                                LyricsLayout.FLUID_GLOW -> 18.dp
                                LyricsLayout.KARAOKE_SPOTLIGHT -> 14.dp
                                LyricsLayout.EDITORIAL_TIMELINE -> 6.dp
                                LyricsLayout.CLASSIC_DEFAULT -> 12.dp
                            }
                        )
                    ) {
                        itemsIndexed(
                            items = syncedLines,
                            key = { index, pair -> "${lyricsLayout.id}_${index}_${pair.first}" }
                        ) { index, (timeMs, lineText) ->
                            val isCurrentLine = index == highlightedIndex
                            val isPastLine = highlightedIndex >= 0 && index < highlightedIndex
                            val cleanText = remember(lineText) { cleanLyricDisplayText(lineText) }

                            when (lyricsLayout) {
                                LyricsLayout.FLUID_GLOW -> {
                                    // Large Editorial Left-Aligned Borderless Lyrics
                                    val lineAlpha by animateFloatAsState(
                                        targetValue = when {
                                            isCurrentLine -> 1.0f
                                            isPastLine -> 0.30f
                                            else -> 0.56f
                                        },
                                        animationSpec = tween(260),
                                        label = "fluid_alpha"
                                    )
                                    val lineScale by animateFloatAsState(
                                        targetValue = if (isCurrentLine) 1.0f else 0.94f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        ),
                                        label = "fluid_scale"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                val targetSeekMs = (timeMs - syncOffsetMs).coerceAtLeast(0L)
                                                userHasManuallyScrolled = false
                                                onUserIsScrollingChange(false)
                                                onSeekTo(targetSeekMs)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = cleanText,
                                            fontSize = if (isCurrentLine) (if (isLandscape) 22.sp else 27.sp) else (if (isLandscape) 16.sp else 20.sp),
                                            lineHeight = if (isCurrentLine) (if (isLandscape) 28.sp else 34.sp) else (if (isLandscape) 22.sp else 26.sp),
                                            fontWeight = if (isCurrentLine) FontWeight.ExtraBold else FontWeight.Bold,
                                            color = if (isCurrentLine) primaryInk else primaryInk.copy(alpha = lineAlpha),
                                            textAlign = TextAlign.Start,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .graphicsLayer {
                                                    scaleX = lineScale
                                                    scaleY = lineScale
                                                }
                                        )
                                    }
                                }

                                LyricsLayout.KARAOKE_SPOTLIGHT -> {
                                    // Centered Spotlight Card + Live Sweep Indicator + NEXT UP cue
                                    val nextLineStart = syncedLines.getOrNull(index + 1)?.first
                                        ?: (timeMs + 4500L)
                                    val lineDuration = (nextLineStart - timeMs).coerceAtLeast(600L)
                                    val lineProgress = if (isCurrentLine) {
                                        ((effectivePosNow - timeMs).toFloat() / lineDuration.toFloat()).coerceIn(0f, 1f)
                                    } else if (isPastLine) 1f else 0f

                                    val nextLinePreview = remember(syncedLines, index) {
                                        syncedLines.getOrNull(index + 1)?.second?.let { cleanLyricDisplayText(it) }
                                    }

                                    if (isCurrentLine) {
                                        Surface(
                                            onClick = {
                                                val targetSeekMs = (timeMs - syncOffsetMs).coerceAtLeast(0L)
                                                userHasManuallyScrolled = false
                                                onUserIsScrollingChange(false)
                                                onSeekTo(targetSeekMs)
                                            },
                                            shape = RoundedCornerShape(22.dp),
                                            color = ambientThemeColor.copy(alpha = if (isDarkTheme) 0.22f else 0.14f),
                                            border = BorderStroke(1.5.dp, ambientThemeColor.copy(alpha = 0.72f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 18.dp, vertical = 16.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Text(
                                                    text = cleanText,
                                                    fontSize = if (isLandscape) 20.sp else 24.sp,
                                                    lineHeight = if (isLandscape) 26.sp else 31.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = primaryInk,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )

                                                // Live Sweep Karaoke Bar
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(0.75f)
                                                        .height(4.dp)
                                                        .clip(CircleShape)
                                                        .background(primaryInk.copy(alpha = 0.14f))
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth(lineProgress)
                                                            .fillMaxHeight()
                                                            .clip(CircleShape)
                                                            .background(
                                                                Brush.horizontalGradient(
                                                                    listOf(ambientThemeColor, BentoAccentPinkText)
                                                                )
                                                            )
                                                    )
                                                }

                                                if (!nextLinePreview.isNullOrBlank()) {
                                                    Text(
                                                        text = "NEXT UP • $nextLinePreview",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = secondaryInk,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    val targetSeekMs = (timeMs - syncOffsetMs).coerceAtLeast(0L)
                                                    userHasManuallyScrolled = false
                                                    onUserIsScrollingChange(false)
                                                    onSeekTo(targetSeekMs)
                                                }
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = cleanText,
                                                fontSize = if (isLandscape) 13.5.sp else 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = primaryInk.copy(alpha = if (isPastLine) 0.34f else 0.54f),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }

                                LyricsLayout.EDITORIAL_TIMELINE -> {
                                    // Monospace Vertical Timestamp Rail + Luminous Node + Accent Stanza Bar
                                    val nodeColor by animateColorAsState(
                                        targetValue = if (isCurrentLine) ambientThemeColor else secondaryInk.copy(alpha = 0.35f),
                                        animationSpec = tween(220),
                                        label = "timeline_node_color"
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (isCurrentLine) ambientThemeColor.copy(alpha = if (isDarkTheme) 0.16f else 0.10f)
                                                else Color.Transparent
                                            )
                                            .clickable {
                                                val targetSeekMs = (timeMs - syncOffsetMs).coerceAtLeast(0L)
                                                userHasManuallyScrolled = false
                                                onUserIsScrollingChange(false)
                                                onSeekTo(targetSeekMs)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Timestamp Badge
                                        Text(
                                            text = formatMsTimestamp(timeMs),
                                            fontSize = 11.sp,
                                            fontWeight = if (isCurrentLine) FontWeight.ExtraBold else FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isCurrentLine) ambientThemeColor else secondaryInk.copy(alpha = 0.7f),
                                            modifier = Modifier.width(42.dp)
                                        )

                                        // Vertical Node & Connector Track
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                            modifier = Modifier.width(14.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(if (isCurrentLine) 10.dp else 7.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isCurrentLine) nodeColor else Color.Transparent)
                                                    .border(1.5.dp, nodeColor, CircleShape)
                                            )
                                        }

                                        // Sharp Active Accent Bar
                                        if (isCurrentLine) {
                                            Box(
                                                modifier = Modifier
                                                    .width(3.dp)
                                                    .heightIn(min = 24.dp)
                                                    .clip(CircleShape)
                                                    .background(ambientThemeColor)
                                            )
                                        }

                                        Text(
                                            text = cleanText,
                                            fontSize = if (isCurrentLine) (if (isLandscape) 17.sp else 19.sp) else (if (isLandscape) 13.5.sp else 15.sp),
                                            fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrentLine) primaryInk else primaryInk.copy(alpha = if (isPastLine) 0.42f else 0.72f),
                                            textAlign = TextAlign.Start,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                LyricsLayout.CLASSIC_DEFAULT -> {}
                            }
                        }
                    }

                    // Floating Sync + Offset + Search Capsule (Auto-reveals when user scrolls away)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = shouldShowSyncButton,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    userHasManuallyScrolled = false
                                    onUserIsScrollingChange(false)
                                    coroutineScope.launch {
                                        if (scrollIndex in syncedLines.indices) {
                                            listState.animateScrollToItem(scrollIndex)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync to current line", tint = BentoOnPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync", color = BentoOnPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            Surface(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    showOffsetDialog = true
                                },
                                shape = CircleShape,
                                color = if (syncOffsetMs != 0L) BentoPrimary else surfaceBg,
                                border = BorderStroke(1.dp, if (syncOffsetMs != 0L) BentoPrimary else borderStrokeColor),
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("lyrics_offset_sync_icon_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Adjust lyrics offset",
                                        tint = if (syncOffsetMs != 0L) BentoOnPrimary else primaryInk,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (hasPlain) {
                // Plain / Unsynced Lyrics customized per LyricsLayout
                val plainLines = remember(currentLyrics?.plainLyrics) {
                    currentLyrics?.plainLyrics?.lines()?.filter { it.isNotBlank() } ?: emptyList()
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(
                        when (lyricsLayout) {
                            LyricsLayout.FLUID_GLOW -> 14.dp
                            LyricsLayout.KARAOKE_SPOTLIGHT -> 12.dp
                            else -> 8.dp
                        }
                    )
                ) {
                    itemsIndexed(plainLines) { idx, line ->
                        val cleanLine = remember(line) { cleanLyricDisplayText(line) }
                        when (lyricsLayout) {
                            LyricsLayout.FLUID_GLOW -> {
                                Text(
                                    text = cleanLine,
                                    fontSize = if (isLandscape) 17.sp else 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryInk.copy(alpha = 0.90f),
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                )
                            }
                            LyricsLayout.KARAOKE_SPOTLIGHT -> {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = surfaceBg.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = cleanLine,
                                        fontSize = if (isLandscape) 15.sp else 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = primaryInk,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                            LyricsLayout.EDITORIAL_TIMELINE -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "%02d".format(idx + 1),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = ambientThemeColor.copy(alpha = 0.8f),
                                        modifier = Modifier.width(28.dp)
                                    )
                                    Text(
                                        text = cleanLine,
                                        fontSize = if (isLandscape) 14.5.sp else 15.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = primaryInk.copy(alpha = 0.9f),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            LyricsLayout.CLASSIC_DEFAULT -> {}
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onPickLrcFile,
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.6f))
                            ) {
                                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pick .LRC", fontSize = 12.sp, color = BentoPrimary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onOpenSearchLyrics,
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.6f))
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Search Online", fontSize = 12.sp, color = BentoPrimary)
                            }
                        }
                    }
                }
            } else {
                // Loading / Empty state
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (currentLyrics?.source == "loading") {
                        CircularProgressIndicator(color = ambientThemeColor, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Fetching Lyrics...",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = secondaryInk
                        )
                    } else {
                        Icon(
                            imageVector = when (lyricsLayout) {
                                LyricsLayout.KARAOKE_SPOTLIGHT -> Icons.Default.Mic
                                LyricsLayout.EDITORIAL_TIMELINE -> Icons.Default.GraphicEq
                                else -> Icons.Default.ChatBubbleOutline
                            },
                            contentDescription = null,
                            tint = ambientThemeColor,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No lyrics available for this track.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = secondaryInk,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onForceFetchLyrics,
                                colors = ButtonDefaults.buttonColors(containerColor = surfaceBg),
                                border = BorderStroke(1.dp, borderStrokeColor)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = primaryInk, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Auto Fetch", fontSize = 12.sp, color = primaryInk)
                            }
                            Button(
                                onClick = onPickLrcFile,
                                colors = ButtonDefaults.buttonColors(containerColor = surfaceBg),
                                border = BorderStroke(1.dp, borderStrokeColor)
                            ) {
                                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = primaryInk, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Local .LRC", fontSize = 12.sp, color = primaryInk)
                            }
                            Button(
                                onClick = onOpenSearchLyrics,
                                colors = ButtonDefaults.buttonColors(containerColor = surfaceBg),
                                border = BorderStroke(1.dp, borderStrokeColor)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = primaryInk, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Search", fontSize = 12.sp, color = primaryInk)
                            }
                        }
                        if (isTranscribeTranslateEnabled) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    transcribeDialogInitialMode = AudioTranscribeMode.TRANSCRIBE
                                    showTranscribeDialog = true
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary.copy(alpha = 0.18f)),
                                border = BorderStroke(1.dp, BentoPrimary)
                            ) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Transcribe Audio (♪)", fontSize = 12.sp, color = BentoPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (showTranscribeDialog) {
            val configuredLang = TranscribeLanguage.fromCode(audioTranscribeTargetLang)
            val configuredMode = AudioTranscribeMode.fromId(audioTranscribeMode)
            val hasExistingLyrics = hasSynced || hasPlain

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

        if (transcribeLyricsPreview != null) {
            TranscribePreviewDialog(
                preview = transcribeLyricsPreview,
                onSave = { onSaveTranscribedLyrics(it) },
                onDiscard = onDiscardTranscribedLyrics,
                onRetranscribe = { onRetranscribedLyrics(it) }
            )
        }

        if (showOffsetDialog) {
            LyricsOffsetDialog(
                song = song,
                currentOffsetMs = syncOffsetMs,
                onOffsetChanged = { newOffset ->
                    syncOffsetMs = newOffset
                    onSaveLyricsOffset?.invoke(song.id, newOffset)
                },
                onDismiss = {
                    showOffsetDialog = false
                    onFlushLyricsOffset?.invoke(song.id)
                },
                syncedLines = syncedLines,
                positionMs = livePositionState?.value ?: positionMs,
                playbackPositionFlow = playbackPositionFlow
            )
        }
    }
}

@Composable
private fun LyricsActionToolbarStrip(
    hasSynced: Boolean,
    hasLyrics: Boolean,
    syncOffsetMs: Long,
    offsetText: String,
    isTranscribeTranslateEnabled: Boolean,
    accentColor: Color,
    primaryInk: Color,
    surfaceBg: Color,
    borderStrokeColor: Color,
    onOpenSearch: () -> Unit,
    onOpenOffset: () -> Unit,
    onOpenTranscribe: () -> Unit,
    onPickLrcFile: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = {
                HapticUtils.performClickHaptic(context)
                onOpenSearch()
            },
            shape = RoundedCornerShape(50),
            color = surfaceBg,
            border = BorderStroke(1.dp, borderStrokeColor),
            modifier = Modifier
                .height(34.dp)
                .testTag("lyrics_top_bar_search_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search lyrics", tint = primaryInk, modifier = Modifier.size(14.dp))
                Text("Search", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = primaryInk)
            }
        }

        if (hasSynced) {
            Surface(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onOpenOffset()
                },
                shape = RoundedCornerShape(50),
                color = if (syncOffsetMs != 0L) accentColor.copy(alpha = 0.22f) else surfaceBg,
                border = BorderStroke(1.dp, if (syncOffsetMs != 0L) accentColor else borderStrokeColor),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("lyrics_offset_tab_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = "Adjust offset",
                        tint = if (syncOffsetMs != 0L) accentColor else primaryInk,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = offsetText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (syncOffsetMs != 0L) accentColor else primaryInk
                    )
                }
            }
        }

        if (isTranscribeTranslateEnabled) {
            Surface(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onOpenTranscribe()
                },
                shape = RoundedCornerShape(50),
                color = BentoPrimary.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("lyrics_transcribe_translate_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (hasLyrics) Icons.Default.Translate else Icons.Default.GraphicEq,
                        contentDescription = if (hasLyrics) "Translate lyrics" else "Transcribe audio",
                        tint = BentoPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (hasLyrics) "Translate" else "Transcribe",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Surface(
            onClick = {
                HapticUtils.performClickHaptic(context)
                onPickLrcFile()
            },
            shape = RoundedCornerShape(50),
            color = BentoPrimary.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
            modifier = Modifier.height(34.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(".LRC", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = BentoPrimary)
            }
        }
    }
}
