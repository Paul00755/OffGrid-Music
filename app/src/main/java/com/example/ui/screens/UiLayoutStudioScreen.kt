package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp as floatLerp
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.ui.components.AlbumArtView
import com.example.ui.components.WaveformMiniPreviewCanvas
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.absoluteValue

enum class LayoutStudioTab(val id: String, val label: String) {
    NOW_PLAYING("NOW_PLAYING", "Now Playing"),
    HOME("HOME", "Home Screen"),
    PLAYLISTS("PLAYLISTS", "Playlists"),
    LYRICS("LYRICS", "Lyrics UI"),
    COMBO("COMBO", "Combo View");

    companion object {
        fun fromId(id: String): LayoutStudioTab =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NOW_PLAYING
    }
}

private data class StudioLiveContext(
    val song: Song?,
    val isPlaying: Boolean,
    val lyricLines: List<Pair<String, String>>, // (timestamp, text)
    val playlistNames: List<String>,
    val recentSongs: List<Song>,
    val isShadowMode: Boolean
) {
    val hasLiveSong: Boolean get() = !isShadowMode && song != null
    val hasLiveLyrics: Boolean get() = !isShadowMode && lyricLines.isNotEmpty()
    val hasLivePlaylists: Boolean get() = !isShadowMode && playlistNames.isNotEmpty()
    val hasLiveRecents: Boolean get() = !isShadowMode && recentSongs.isNotEmpty()
}

/**
 * Dedicated full-screen Swipeable Live UI Layout Preview Studio.
 * Renders real currently playing artwork, track metadata, audio format, synced lyrics,
 * and active Home Feed sections when accessed from Settings — and gracefully falls back
 * to exact-structure shadow previews during Onboarding or when nothing is playing.
 */
@Composable
fun UiLayoutStudioScreen(
    initialTabId: String = "NOW_PLAYING",
    currentNowPlayingLayout: String,
    currentHomeScreenLayout: String,
    currentPlaylistsLayout: String,
    currentLyricsLayout: String,
    waveformStyle: String,
    themeMode: String,
    isGradientEffect: Boolean,
    isGlassEffect: Boolean,
    useDarkColors: Boolean,
    showVolumeBar: Boolean,
    showAdvancedSongInfo: Boolean,
    homeSections: List<String>,
    liveSong: Song? = null,
    liveIsPlaying: Boolean = false,
    liveLyrics: Lyrics? = null,
    livePlaylistNames: List<String> = emptyList(),
    liveRecentSongs: List<Song> = emptyList(),
    isOnboardingShadowMode: Boolean = false,
    onConfirmLayouts: (
        nowPlayingLayout: String,
        homeScreenLayout: String,
        playlistsLayout: String,
        lyricsLayout: String
    ) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler(enabled = true) { onBack() }

    var activeTab by remember(initialTabId) { mutableStateOf(LayoutStudioTab.fromId(initialTabId)) }

    var stagedNowPlaying by remember(currentNowPlayingLayout) {
        mutableStateOf(NowPlayingLayout.fromId(currentNowPlayingLayout).id)
    }
    var stagedHome by remember(currentHomeScreenLayout) {
        mutableStateOf(HomeScreenLayout.fromId(currentHomeScreenLayout).id)
    }
    var stagedPlaylists by remember(currentPlaylistsLayout) {
        mutableStateOf(PlaylistsLayout.fromId(currentPlaylistsLayout).id)
    }
    var stagedLyrics by remember(currentLyricsLayout) {
        mutableStateOf(LyricsLayout.fromId(currentLyricsLayout).id)
    }

    val pendingChangeCount = remember(
        stagedNowPlaying, stagedHome, stagedPlaylists, stagedLyrics,
        currentNowPlayingLayout, currentHomeScreenLayout, currentPlaylistsLayout, currentLyricsLayout
    ) {
        var count = 0
        if (!stagedNowPlaying.equals(NowPlayingLayout.fromId(currentNowPlayingLayout).id, true)) count++
        if (!stagedHome.equals(HomeScreenLayout.fromId(currentHomeScreenLayout).id, true)) count++
        if (!stagedPlaylists.equals(PlaylistsLayout.fromId(currentPlaylistsLayout).id, true)) count++
        if (!stagedLyrics.equals(LyricsLayout.fromId(currentLyricsLayout).id, true)) count++
        count
    }

    val parsedLyricPreview = remember(liveLyrics, isOnboardingShadowMode) {
        if (isOnboardingShadowMode || liveLyrics == null) {
            emptyList()
        } else {
            val synced = liveLyrics.parseSyncedLines().filter { it.second.isNotBlank() && it.second != "♪" }
            if (synced.isNotEmpty()) {
                synced.take(5).map { (ms, text) ->
                    val sec = (ms / 1000L).coerceAtLeast(0L)
                    String.format(Locale.US, "%02d:%02d", sec / 60L, sec % 60L) to text.trim()
                }
            } else {
                val plain = liveLyrics.plainLyrics
                    ?.lines()
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                plain.take(5).mapIndexed { idx, line ->
                    val sec = 15 + idx * 9
                    String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60) to line
                }
            }
        }
    }

    val liveCtx = remember(liveSong, liveIsPlaying, parsedLyricPreview, livePlaylistNames, liveRecentSongs, isOnboardingShadowMode) {
        StudioLiveContext(
            song = liveSong,
            isPlaying = liveIsPlaying,
            lyricLines = parsedLyricPreview,
            playlistNames = livePlaylistNames,
            recentSongs = liveRecentSongs,
            isShadowMode = isOnboardingShadowMode
        )
    }

    val isLightBg = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val accent = BentoPrimary
    val onAccent = BentoOnPrimary

    val effectiveFeedSections = remember(homeSections) {
        val raw = if (homeSections.isEmpty()) {
            listOf("CONTINUE_LISTENING", "QUICK_ACTIONS", "STATS", "RECENTLY_PLAYED", "MOST_PLAYED")
        } else {
            homeSections
        }
        raw.filter { !it.startsWith("HIDDEN:") }.ifEmpty {
            listOf("CONTINUE_LISTENING", "QUICK_ACTIONS", "RECENTLY_PLAYED")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("ui_layout_studio_screen")
    ) {
        // Top App Bar with Back button on the left
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onBack()
                },
                modifier = Modifier
                    .size(44.dp)
                    .testTag("layout_studio_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = BentoTextPrimary
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isOnboardingShadowMode) "Choose Your UI & Feel" else "Screen Layout Studio",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        isOnboardingShadowMode -> "Preview exact screen structures before scanning"
                        liveCtx.hasLiveSong -> "Live preview with ${liveSong?.displayTitle}"
                        else -> "Structural preview with your active theme & feed"
                    },
                    fontSize = 11.5.sp,
                    color = BentoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (pendingChangeCount > 0) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = accent.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "$pendingChangeCount staged",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Category Switcher Row (Now Playing, Home Screen, Playlists, Lyrics UI, Combo View)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(LayoutStudioTab.entries) { _, tab ->
                val isSelected = activeTab == tab
                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        activeTab = tab
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = if (isSelected) accent else BentoCardSurface,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) accent else BentoBorder.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.testTag("studio_tab_${tab.id.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (tab == LayoutStudioTab.COMBO) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesomeMosaic,
                                contentDescription = null,
                                tint = if (isSelected) onAccent else accent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = tab.label,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) onAccent else BentoTextPrimary
                        )
                    }
                }
            }
        }

        // Resolve page list for current tab
        val pageCount = when (activeTab) {
            LayoutStudioTab.NOW_PLAYING -> NowPlayingLayout.entries.size
            LayoutStudioTab.HOME -> HomeScreenLayout.entries.size
            LayoutStudioTab.PLAYLISTS -> PlaylistsLayout.entries.size
            LayoutStudioTab.LYRICS -> LyricsLayout.entries.size
            LayoutStudioTab.COMBO -> 4
        }

        val initialPageForTab = remember(activeTab) {
            when (activeTab) {
                LayoutStudioTab.NOW_PLAYING -> NowPlayingLayout.entries.indexOfFirst { it.id.equals(stagedNowPlaying, true) }.coerceAtLeast(0)
                LayoutStudioTab.HOME -> HomeScreenLayout.entries.indexOfFirst { it.id.equals(stagedHome, true) }.coerceAtLeast(0)
                LayoutStudioTab.PLAYLISTS -> PlaylistsLayout.entries.indexOfFirst { it.id.equals(stagedPlaylists, true) }.coerceAtLeast(0)
                LayoutStudioTab.LYRICS -> LyricsLayout.entries.indexOfFirst { it.id.equals(stagedLyrics, true) }.coerceAtLeast(0)
                LayoutStudioTab.COMBO -> 0
            }
        }

        key(activeTab) {
            val pagerState = rememberPagerState(
                initialPage = initialPageForTab.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                pageCount = { pageCount }
            )

            LaunchedEffect(pagerState.currentPage, activeTab) {
                when (activeTab) {
                    LayoutStudioTab.NOW_PLAYING -> stagedNowPlaying = NowPlayingLayout.entries[pagerState.currentPage].id
                    LayoutStudioTab.HOME -> stagedHome = HomeScreenLayout.entries[pagerState.currentPage].id
                    LayoutStudioTab.PLAYLISTS -> stagedPlaylists = PlaylistsLayout.entries[pagerState.currentPage].id
                    LayoutStudioTab.LYRICS -> stagedLyrics = LyricsLayout.entries[pagerState.currentPage].id
                    LayoutStudioTab.COMBO -> Unit
                }
            }

            val currentTitle = when (activeTab) {
                LayoutStudioTab.NOW_PLAYING -> NowPlayingLayout.entries[pagerState.currentPage].displayName
                LayoutStudioTab.HOME -> HomeScreenLayout.entries[pagerState.currentPage].displayName
                LayoutStudioTab.PLAYLISTS -> PlaylistsLayout.entries[pagerState.currentPage].displayName
                LayoutStudioTab.LYRICS -> LyricsLayout.entries[pagerState.currentPage].displayName
                LayoutStudioTab.COMBO -> when (pagerState.currentPage) {
                    0 -> "Home • ${HomeScreenLayout.fromId(stagedHome).displayName}"
                    1 -> "Now Playing • ${NowPlayingLayout.fromId(stagedNowPlaying).displayName}"
                    2 -> "Lyrics • ${LyricsLayout.fromId(stagedLyrics).displayName}"
                    else -> "Playlists • ${PlaylistsLayout.fromId(stagedPlaylists).displayName}"
                }
            }

            val currentSubtitle = when (activeTab) {
                LayoutStudioTab.NOW_PLAYING -> NowPlayingLayout.entries[pagerState.currentPage].subtitle
                LayoutStudioTab.HOME -> HomeScreenLayout.entries[pagerState.currentPage].subtitle
                LayoutStudioTab.PLAYLISTS -> PlaylistsLayout.entries[pagerState.currentPage].subtitle
                LayoutStudioTab.LYRICS -> LyricsLayout.entries[pagerState.currentPage].subtitle
                LayoutStudioTab.COMBO -> "Combined suite using your selected theme, gradient & feed"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = currentSubtitle,
                    fontSize = 11.5.sp,
                    color = BentoTextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val targetMockupWidth = (maxHeight * 0.49f).coerceAtMost(maxWidth * 0.76f)
                val responsiveHorizontalPadding = ((maxWidth - targetMockupWidth) / 2f).coerceIn(24.dp, 220.dp)
                val responsivePageSpacing = if (maxWidth >= 600.dp) 22.dp else 14.dp

                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = responsiveHorizontalPadding),
                    pageSpacing = responsivePageSpacing,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(if (activeTab == LayoutStudioTab.LYRICS) "lyrics_layout_preview_row" else "layout_studio_pager")
                ) { page ->
                    val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                    val scale = floatLerp(0.90f, 1.0f, 1f - pageOffset)
                    val alpha = floatLerp(0.55f, 1.0f, 1f - pageOffset)

                    StudioPhoneMockupFrame(
                        isFocused = page == pagerState.currentPage,
                        accent = accent,
                        isLight = isLightBg,
                        themeMode = themeMode,
                        isGradientEffect = isGradientEffect,
                        isGlassEffect = isGlassEffect,
                        useDarkColors = useDarkColors,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(0.49f, matchHeightConstraintsFirst = true)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                            .clickable {
                                if (page != pagerState.currentPage) {
                                    scope.launch { pagerState.animateScrollToPage(page) }
                                }
                            }
                    ) { palette ->
                        when (activeTab) {
                            LayoutStudioTab.NOW_PLAYING -> LiveNowPlayingMockup(
                                layout = NowPlayingLayout.entries[page],
                                palette = palette,
                                waveformStyle = waveformStyle,
                                showVolumeBar = showVolumeBar,
                                showAdvancedSongInfo = showAdvancedSongInfo,
                                liveCtx = liveCtx
                            )
                            LayoutStudioTab.HOME -> LiveHomeMockup(
                                layout = HomeScreenLayout.entries[page],
                                palette = palette,
                                waveformStyle = waveformStyle,
                                feedSections = effectiveFeedSections,
                                liveCtx = liveCtx
                            )
                            LayoutStudioTab.PLAYLISTS -> LivePlaylistsMockup(
                                layout = PlaylistsLayout.entries[page],
                                palette = palette,
                                liveCtx = liveCtx
                            )
                            LayoutStudioTab.LYRICS -> LiveLyricsMockup(
                                layout = LyricsLayout.entries[page],
                                palette = palette,
                                liveCtx = liveCtx
                            )
                            LayoutStudioTab.COMBO -> when (page) {
                                0 -> LiveHomeMockup(
                                    layout = HomeScreenLayout.fromId(stagedHome),
                                    palette = palette,
                                    waveformStyle = waveformStyle,
                                    feedSections = effectiveFeedSections,
                                    liveCtx = liveCtx
                                )
                                1 -> LiveNowPlayingMockup(
                                    layout = NowPlayingLayout.fromId(stagedNowPlaying),
                                    palette = palette,
                                    waveformStyle = waveformStyle,
                                    showVolumeBar = showVolumeBar,
                                    showAdvancedSongInfo = showAdvancedSongInfo,
                                    liveCtx = liveCtx
                                )
                                2 -> LiveLyricsMockup(
                                    layout = LyricsLayout.fromId(stagedLyrics),
                                    palette = palette,
                                    liveCtx = liveCtx
                                )
                                else -> LivePlaylistsMockup(
                                    layout = PlaylistsLayout.fromId(stagedPlaylists),
                                    palette = palette,
                                    liveCtx = liveCtx
                                )
                            }
                        }
                    }
                }
            }

            if (activeTab != LayoutStudioTab.COMBO) {
                val quickLabels: List<Pair<Int, String>> = when (activeTab) {
                    LayoutStudioTab.NOW_PLAYING -> NowPlayingLayout.entries.mapIndexed { idx, l -> idx to l.displayName }
                    LayoutStudioTab.HOME -> HomeScreenLayout.entries.mapIndexed { idx, l -> idx to l.displayName }
                    LayoutStudioTab.PLAYLISTS -> PlaylistsLayout.entries.mapIndexed { idx, l -> idx to l.displayName }
                    LayoutStudioTab.LYRICS -> LyricsLayout.entries.mapIndexed { idx, l -> idx to l.displayName }
                    LayoutStudioTab.COMBO -> emptyList()
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    itemsIndexed(quickLabels) { _, (idx, name) ->
                        val selected = idx == pagerState.currentPage
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                scope.launch { pagerState.animateScrollToPage(idx) }
                            },
                            shape = CircleShape,
                            color = if (selected) accent.copy(alpha = 0.2f) else BentoCardSurface,
                            border = BorderStroke(1.dp, if (selected) accent else BentoBorder.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = name,
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) accent else BentoTextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pageCount) { idx ->
                        val active = idx == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .height(6.dp)
                                .width(if (active) 20.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (active) accent else BentoTextSubtle.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }

        // Bottom Confirmation Bar
        Surface(
            color = BentoCardSurface.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (activeTab != LayoutStudioTab.COMBO) {
                        OutlinedButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                activeTab = LayoutStudioTab.COMBO
                            },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.65f)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesomeMosaic,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Preview Combo",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary
                            )
                        }
                    } else {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = "Full Suite Ready",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "${HomeScreenLayout.fromId(stagedHome).displayName} • ${NowPlayingLayout.fromId(stagedNowPlaying).displayName}",
                                fontSize = 10.5.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onConfirmLayouts(
                                stagedNowPlaying,
                                stagedHome,
                                stagedPlaylists,
                                stagedLyrics
                            )
                            onBack()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = onAccent
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 11.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("layout_studio_confirm_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (pendingChangeCount > 1) "Set All ($pendingChangeCount)" else "Set",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private data class StudioMockPalette(
    val bgTop: Color,
    val bgBottom: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val artworkTop: Color,
    val artworkBottom: Color,
    val shadowBlock: Color,
    val isLight: Boolean
)

@Composable
private fun StudioPhoneMockupFrame(
    isFocused: Boolean,
    accent: Color,
    isLight: Boolean,
    themeMode: String,
    isGradientEffect: Boolean,
    isGlassEffect: Boolean,
    useDarkColors: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (StudioMockPalette) -> Unit
) {
    val isAmoled = themeMode.equals("AMOLED", ignoreCase = true)
    // Match Theme.kt exactly: Dark = Midnight Obsidian lite-dark slate; AMOLED = pure pitch black
    val baseBg = when {
        isLight -> Color(0xFFF6F7FB)
        isAmoled -> Color(0xFF000000)
        else -> Color(0xFF0E111A)
    }
    val tintStrength = when {
        !isGradientEffect -> 0f
        isLight -> 0.22f
        useDarkColors -> 0.34f
        else -> 0.20f
    }
    val bgTop = if (isGradientEffect) lerp(baseBg, accent, tintStrength) else baseBg
    val bgBottom = if (isGradientEffect) lerp(baseBg, accent, tintStrength * 0.45f) else baseBg

    val cardBase = when {
        isLight -> Color.White
        isAmoled -> Color(0xFF090A0F)
        else -> Color(0xFF161A26)
    }
    val cardBg = if (isGlassEffect) {
        if (isLight) Color.White.copy(alpha = 0.72f) else accent.copy(alpha = 0.12f)
    } else {
        lerp(cardBase, accent, if (isGradientEffect) 0.08f else 0f)
    }
    val cardBorder = if (isGlassEffect) {
        if (isLight) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.22f)
    } else {
        accent.copy(alpha = 0.22f)
    }

    val palette = StudioMockPalette(
        bgTop = bgTop,
        bgBottom = bgBottom,
        cardBg = cardBg,
        cardBorder = cardBorder,
        textPrimary = if (isLight) Color(0xFF10131C) else Color.White,
        textSecondary = if (isLight) Color(0xFF566074) else Color.White.copy(alpha = 0.65f),
        accent = accent,
        onAccent = if (accent.luminance() > 0.48f) Color(0xFF0A0B10) else Color.White,
        artworkTop = lerp(accent, Color(0xFF3A7CA5), 0.35f),
        artworkBottom = lerp(accent, Color(0xFF172A3A), 0.55f),
        shadowBlock = if (isLight) Color(0xFF10131C).copy(alpha = 0.14f) else Color.White.copy(alpha = 0.16f),
        isLight = isLight
    )

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = baseBg,
        shadowElevation = if (isFocused) 14.dp else 4.dp,
        border = BorderStroke(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) accent.copy(alpha = 0.85f) else BentoBorder.copy(alpha = 0.4f)
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom)))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Realistic Phone Status Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "11:04",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary.copy(alpha = 0.85f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Wifi, null, tint = palette.textPrimary.copy(alpha = 0.8f), modifier = Modifier.size(9.dp))
                        Icon(Icons.Default.SignalCellularAlt, null, tint = palette.textPrimary.copy(alpha = 0.8f), modifier = Modifier.size(9.dp))
                        Icon(Icons.Default.BatteryFull, null, tint = palette.textPrimary.copy(alpha = 0.8f), modifier = Modifier.size(9.dp))
                    }
                }

                // Mockup Screen Body
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    content(palette)
                }

                // Bottom Gesture Pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp, top = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(palette.textPrimary.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
private fun MockArtworkSurface(
    song: Song?,
    isShadow: Boolean,
    palette: StudioMockPalette,
    cornerRadius: Int,
    modifier: Modifier = Modifier,
    fallbackIconSize: Dp = 36.dp
) {
    if (!isShadow && song != null) {
        AlbumArtView(
            song = song,
            modifier = modifier,
            cornerRadius = cornerRadius
        )
    } else {
        Box(
            modifier = modifier.background(
                Brush.linearGradient(listOf(palette.artworkTop, palette.artworkBottom))
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.34f),
                modifier = Modifier.size(fallbackIconSize)
            )
        }
    }
}

@Composable
private fun ShadowBar(
    widthFraction: Float,
    height: Dp,
    color: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(CircleShape)
            .background(color)
    )
}

private fun formatPreviewDuration(durationMs: Long): String {
    val sec = (durationMs / 1000L).coerceAtLeast(0L)
    return if (sec > 0L) String.format(Locale.US, "%d:%02d", sec / 60L, sec % 60L) else "3:58"
}

private fun previewFormatBadge(song: Song?): String {
    if (song == null) return "FLAC • 44.1 kHz • 940 kbps"
    val fmt = song.format.ifBlank {
        song.path.substringAfterLast('.', "").uppercase(Locale.US).ifBlank { "AUDIO" }
    }
    val kbps = if (song.bitrate > 0) song.bitrate else 320
    return "$fmt • 44.1 kHz • $kbps kbps"
}

@Composable
private fun LiveNowPlayingMockup(
    layout: NowPlayingLayout,
    palette: StudioMockPalette,
    waveformStyle: String,
    showVolumeBar: Boolean,
    showAdvancedSongInfo: Boolean,
    liveCtx: StudioLiveContext
) {
    val song = liveCtx.song
    val useLive = liveCtx.hasLiveSong
    val durationLabel = formatPreviewDuration(song?.durationMs ?: 238_000L)
    val playIcon = if (useLive && liveCtx.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow

    when (layout) {
        NowPlayingLayout.MODERN_BENTO -> {
            // Exact Modern Bento structure: full-bleed top art dissolving into bottom tinted stage
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.56f)
                ) {
                    MockArtworkSurface(
                        song = song,
                        isShadow = !useLive,
                        palette = palette,
                        cornerRadius = 0,
                        modifier = Modifier.fillMaxSize(),
                        fallbackIconSize = 44.dp
                    )
                    // Top-left collapse chevron pill
                    Box(
                        modifier = Modifier
                            .padding(10.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.38f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                    // Smooth seamless dissolve gradient into lower stage
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.46f)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, palette.bgBottom)))
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (useLive && song != null) {
                                Text(song.displayTitle, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.displayArtist, fontSize = 9.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                ShadowBar(0.74f, 8.dp, palette.textPrimary.copy(alpha = 0.72f))
                                ShadowBar(0.48f, 6.dp, palette.textSecondary.copy(alpha = 0.45f))
                            }
                            if (showAdvancedSongInfo) {
                                Text(
                                    text = previewFormatBadge(if (useLive) song else null),
                                    fontSize = 7.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.textSecondary.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Box(
                            modifier = Modifier.size(21.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.MoreVert, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp)) }
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier.size(21.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (useLive && song?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null,
                                tint = if (useLive && song?.isFavorite == true) BentoAccentPinkText else palette.textPrimary,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }

                    // Waveform + 0:00 / duration timestamps
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        WaveformMiniPreviewCanvas(
                            styleId = waveformStyle,
                            accentColor = palette.textPrimary,
                            trackColor = palette.textPrimary.copy(alpha = 0.24f),
                            modifier = Modifier.fillMaxWidth().height(16.dp)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1:24", fontSize = 7.sp, color = palette.textSecondary)
                            Text(durationLabel, fontSize = 7.sp, color = palette.textSecondary)
                        }
                    }

                    // Center Transport
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FastRewind, null, tint = palette.textPrimary, modifier = Modifier.size(19.dp))
                        Icon(playIcon, null, tint = palette.textPrimary, modifier = Modifier.size(25.dp))
                        Icon(Icons.Default.FastForward, null, tint = palette.textPrimary, modifier = Modifier.size(19.dp))
                    }

                    if (showVolumeBar) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.VolumeDown, null, tint = palette.textSecondary, modifier = Modifier.size(9.dp))
                            Box(modifier = Modifier.weight(1f).height(2.5.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.22f))) {
                                Box(modifier = Modifier.fillMaxWidth(0.68f).fillMaxHeight().background(palette.accent))
                            }
                            Icon(Icons.Default.VolumeUp, null, tint = palette.textSecondary, modifier = Modifier.size(9.dp))
                        }
                    }

                    // Audio Route Label
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VolumeUp, null, tint = palette.textSecondary, modifier = Modifier.size(8.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Phone speaker", fontSize = 7.5.sp, color = palette.textSecondary)
                    }

                    // Exact 5-item bottom action dock
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                        Icon(Icons.Default.Shuffle, null, tint = palette.textPrimary, modifier = Modifier.size(12.dp))
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.textPrimary.copy(alpha = 0.13f)) {
                            Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Icon(Icons.Default.Speaker, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                                Icon(Icons.Default.Timer, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                            }
                        }
                        Icon(Icons.Default.Repeat, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                        Icon(Icons.Default.Lyrics, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }

        NowPlayingLayout.VINYL_TURNTABLE -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(15.dp))
                    Text("NOW PLAYING", fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = palette.textSecondary)
                    Icon(Icons.Default.MoreVert, null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
                }

                // Rotating Vinyl Platter with Concentric Grooves + Tonearm Needle
                Box(
                    modifier = Modifier.size(134.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(124.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Color(0xFF282C38), Color(0xFF0A0B10))))
                            .border(2.dp, palette.accent.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        // Subtle vinyl grooves
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            listOf(0.42f, 0.36f, 0.30f).forEach { rFrac ->
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.08f),
                                    radius = size.minDimension * rFrac,
                                    center = Offset(cx, cy),
                                    style = Stroke(width = 0.8.dp.toPx())
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            MockArtworkSurface(
                                song = song,
                                isShadow = !useLive,
                                palette = palette,
                                cornerRadius = 999,
                                modifier = Modifier.fillMaxSize(),
                                fallbackIconSize = 20.dp
                            )
                            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF0A0B10)))
                        }
                    }

                    // Tonearm pivot & stylus on top-right
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pivot = Offset(size.width * 0.88f, size.height * 0.14f)
                        val tip = Offset(size.width * 0.67f, size.height * 0.48f)
                        drawCircle(color = Color(0xFFB8C0D0), radius = 4.dp.toPx(), center = pivot)
                        drawLine(
                            color = Color(0xFFD0D7E5),
                            start = pivot,
                            end = tip,
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (useLive && song != null) {
                        Text(song.displayTitle, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.displayArtist, fontSize = 9.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        ShadowBar(0.60f, 8.dp, palette.textPrimary.copy(alpha = 0.72f))
                        ShadowBar(0.40f, 6.dp, palette.textSecondary.copy(alpha = 0.45f))
                    }
                    if (showAdvancedSongInfo) {
                        Text(previewFormatBadge(if (useLive) song else null), fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = palette.accent, maxLines = 1)
                    }
                }

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    WaveformMiniPreviewCanvas(
                        styleId = waveformStyle,
                        accentColor = palette.accent,
                        trackColor = palette.textPrimary.copy(alpha = 0.22f),
                        modifier = Modifier.fillMaxWidth().height(16.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1:24", fontSize = 7.sp, color = palette.textSecondary)
                        Text(durationLabel, fontSize = 7.sp, color = palette.textSecondary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shuffle, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                    Icon(Icons.Default.SkipPrevious, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                    Box(
                        modifier = Modifier.size(32.dp).clip(CircleShape).background(palette.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(playIcon, null, tint = palette.onAccent, modifier = Modifier.size(17.dp))
                    }
                    Icon(Icons.Default.SkipNext, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                    Icon(Icons.Default.Repeat, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Speaker, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                    Icon(Icons.Default.Timer, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                    Icon(Icons.Default.Lyrics, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                }
            }
        }

        NowPlayingLayout.MINIMAL_GLASS -> {
            // Exact Minimal Glass structure: full ambient backdrop + floating glass art frame + frosted control sheet
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(palette.artworkTop, palette.bgBottom)))
                    .padding(11.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text("GLASS STAGE", fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.72f))
                    Icon(Icons.Default.MoreVert, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }

                // Centered Glass-Framed Album Cover
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 26.dp)
                        .size(116.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.2.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                ) {
                    MockArtworkSurface(
                        song = song,
                        isShadow = !useLive,
                        palette = palette,
                        cornerRadius = 18,
                        modifier = Modifier.fillMaxSize(),
                        fallbackIconSize = 36.dp
                    )
                }

                // Frosted Glass Bottom Control Card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.44f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (useLive && song != null) {
                                    Text(song.displayTitle, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.displayArtist, fontSize = 8.5.sp, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    ShadowBar(0.75f, 7.dp, Color.White.copy(alpha = 0.75f))
                                    ShadowBar(0.45f, 5.dp, Color.White.copy(alpha = 0.42f))
                                }
                            }
                            Icon(
                                if (useLive && song?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null,
                                tint = palette.accent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            WaveformMiniPreviewCanvas(
                                styleId = waveformStyle,
                                accentColor = Color.White,
                                trackColor = Color.White.copy(alpha = 0.28f),
                                modifier = Modifier.fillMaxWidth().height(15.dp)
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("1:24", fontSize = 6.5.sp, color = Color.White.copy(alpha = 0.7f))
                                Text(durationLabel, fontSize = 6.5.sp, color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Shuffle, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                            Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(17.dp))
                            Box(
                                modifier = Modifier.size(29.dp).clip(CircleShape).background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(playIcon, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            }
                            Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(17.dp))
                            Icon(Icons.Default.Lyrics, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }

        NowPlayingLayout.NEON_WAVEFORM -> {
            // Exact Neon Studio layout: top handle, large rounded cover, waveform + timestamps, centered info, 5-icon transport, pill dock
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(15.dp))
                    Box(modifier = Modifier.width(24.dp).height(3.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.35f)))
                    Icon(Icons.Default.MoreHoriz, null, tint = palette.textPrimary, modifier = Modifier.size(15.dp))
                }

                // Rounded Floating Cover Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.90f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, palette.accent.copy(alpha = 0.32f), RoundedCornerShape(16.dp))
                ) {
                    MockArtworkSurface(
                        song = song,
                        isShadow = !useLive,
                        palette = palette,
                        cornerRadius = 16,
                        modifier = Modifier.fillMaxSize(),
                        fallbackIconSize = 40.dp
                    )
                }

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    WaveformMiniPreviewCanvas(
                        styleId = waveformStyle,
                        accentColor = if (palette.isLight) palette.accent else Color.White,
                        trackColor = palette.textPrimary.copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth().height(16.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1:24", fontSize = 7.sp, color = palette.textSecondary)
                        Text(durationLabel, fontSize = 7.sp, color = palette.textSecondary)
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (useLive && song != null) {
                        Text(song.displayTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.displayArtist, fontSize = 8.5.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        ShadowBar(0.64f, 7.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                        ShadowBar(0.42f, 5.5.dp, palette.textSecondary.copy(alpha = 0.45f))
                    }
                    if (showAdvancedSongInfo) {
                        Text(previewFormatBadge(if (useLive) song else null), fontSize = 6.5.sp, fontFamily = FontFamily.Monospace, color = palette.textSecondary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shuffle, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                    Icon(Icons.Default.SkipPrevious, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                    Icon(playIcon, null, tint = palette.textPrimary, modifier = Modifier.size(22.dp))
                    Icon(Icons.Default.SkipNext, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                    Icon(
                        if (useLive && song?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
                        tint = if (useLive && song?.isFavorite == true) BentoAccentPinkText else palette.textPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MoreHoriz, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                    Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder)) {
                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Podcasts, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                            Icon(Icons.Default.Repeat, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                            Icon(Icons.Default.Timer, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                        }
                    }
                    Icon(Icons.Default.Lyrics, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun LiveHomeMockup(
    layout: HomeScreenLayout,
    palette: StudioMockPalette,
    waveformStyle: String,
    feedSections: List<String>,
    liveCtx: StudioLiveContext
) {
    val song = liveCtx.song
    val useLive = liveCtx.hasLiveSong

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.cardBg), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Menu, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                }
                Text("OffGrid Music", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1)
            }
            Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Settings, null, tint = palette.onAccent, modifier = Modifier.size(10.dp))
            }
        }

        // Primary Hero Block varies by HomeScreenLayout
        when (layout) {
            HomeScreenLayout.HERO_SPOTLIGHT -> {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.cardBg,
                    border = BorderStroke(0.8.dp, palette.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.size(34.dp).clip(CircleShape)) {
                                MockArtworkSurface(
                                    song = song,
                                    isShadow = !useLive,
                                    palette = palette,
                                    cornerRadius = 999,
                                    modifier = Modifier.fillMaxSize(),
                                    fallbackIconSize = 15.dp
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Continue Listening", fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = palette.accent, maxLines = 1)
                                if (useLive && song != null) {
                                    Text(song.displayTitle, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    ShadowBar(0.75f, 5.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                                }
                            }
                            Icon(Icons.Default.FavoriteBorder, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                        }
                        WaveformMiniPreviewCanvas(
                            styleId = waveformStyle,
                            accentColor = Color.White,
                            trackColor = Color.White.copy(alpha = 0.26f),
                            modifier = Modifier.fillMaxWidth().height(12.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(999.dp)).background(palette.accent),
                                contentAlignment = Alignment.Center
                            ) { Text("Play", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = palette.onAccent) }
                            Box(
                                modifier = Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(999.dp))
                                    .border(0.8.dp, palette.cardBorder, RoundedCornerShape(999.dp)),
                                contentAlignment = Alignment.Center
                            ) { Text("Shuffle Mix", fontSize = 8.sp, color = palette.textPrimary) }
                        }
                    }
                }
            }

            HomeScreenLayout.BENTO_DYNAMIC -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.accent.copy(alpha = 0.20f),
                        border = BorderStroke(0.8.dp, palette.accent.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1.25f).height(64.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))) {
                                MockArtworkSurface(song, !useLive, palette, 8, Modifier.fillMaxSize(), 14.dp)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("BENTO MIX", fontSize = 6.5.sp, fontWeight = FontWeight.Bold, color = palette.accent)
                                Text(
                                    text = if (useLive && song != null) song.displayTitle else "Instant Play",
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(shape = RoundedCornerShape(10.dp), color = palette.cardBg, modifier = Modifier.fillMaxWidth().height(29.dp)) {
                            Row(modifier = Modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Favorite, null, tint = palette.accent, modifier = Modifier.size(10.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Favorites", fontSize = 8.sp, color = palette.textPrimary)
                            }
                        }
                        Surface(shape = RoundedCornerShape(10.dp), color = palette.cardBg, modifier = Modifier.fillMaxWidth().height(29.dp)) {
                            Row(modifier = Modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Shuffle, null, tint = palette.accent, modifier = Modifier.size(10.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Shuffle All", fontSize = 8.sp, color = palette.textPrimary)
                            }
                        }
                    }
                }
            }

            HomeScreenLayout.MINIMAL_FEED -> {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("Library", "Playlists", "Favorites").forEach { chip ->
                            Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.6.dp, palette.cardBorder)) {
                                Text(chip, fontSize = 7.5.sp, color = palette.textPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(10.dp), color = palette.cardBg, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))) {
                                MockArtworkSurface(song, !useLive, palette, 6, Modifier.fillMaxSize(), 12.dp)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (useLive && song != null) song.displayTitle else "Quick Stream",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (useLive && song != null) song.displayArtist else "Clean dense list",
                                    fontSize = 7.sp,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Default.PlayArrow, null, tint = palette.accent, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            HomeScreenLayout.STUDIO_DASHBOARD -> {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.cardBg,
                    border = BorderStroke(0.8.dp, palette.accent.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("STUDIO DECK", fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = palette.accent)
                            Text(if (useLive && song != null) song.displayTitle else "42m today", fontSize = 7.5.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        WaveformMiniPreviewCanvas(
                            styleId = waveformStyle,
                            accentColor = palette.accent,
                            trackColor = palette.textPrimary.copy(alpha = 0.2f),
                            modifier = Modifier.fillMaxWidth().height(16.dp)
                        )
                    }
                }
            }
        }

        // Dynamic User Home Feed Sections preview
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            feedSections.take(3).forEachIndexed { secIdx, rawId ->
                val cleanId = rawId.removePrefix("HIDDEN:").uppercase()
                val label = when {
                    cleanId.contains("QUICK") -> "Quick Actions"
                    cleanId.contains("STAT") -> "Listening Statistics"
                    cleanId.contains("SPEED") -> "Speed Dial"
                    cleanId.contains("RECENTLY_ADDED") -> "Recently Added"
                    cleanId.contains("RECENT") -> "Recently Played"
                    cleanId.contains("MOST") -> "Most Played"
                    cleanId.contains("ALBUM") -> "Top Albums"
                    cleanId.contains("ARTIST") -> "Top Artists"
                    else -> "Your Mixes"
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.width(2.5.dp).height(9.dp).clip(CircleShape).background(palette.accent))
                        Text(label, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(2) { idx ->
                            val liveRecent = if (liveCtx.hasLiveRecents) liveCtx.recentSongs.getOrNull(secIdx * 2 + idx) else null
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = palette.cardBg,
                                border = BorderStroke(0.5.dp, palette.cardBorder),
                                modifier = Modifier.weight(1f).height(34.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(modifier = Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))) {
                                        MockArtworkSurface(liveRecent, liveRecent == null, palette, 5, Modifier.fillMaxSize(), 9.dp)
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (liveRecent != null) {
                                            Text(liveRecent.displayTitle, fontSize = 7.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(liveRecent.displayArtist, fontSize = 6.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        } else {
                                            ShadowBar(0.82f, 4.dp, palette.textPrimary.copy(alpha = 0.65f))
                                            ShadowBar(0.55f, 3.dp, palette.textSecondary.copy(alpha = 0.42f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Floating Mini Player
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = palette.cardBg,
            border = BorderStroke(0.6.dp, palette.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(modifier = Modifier.size(17.dp).clip(RoundedCornerShape(4.dp))) {
                        MockArtworkSurface(song, !useLive, palette, 4, Modifier.fillMaxSize(), 9.dp)
                    }
                    if (useLive && song != null) {
                        Text(song.displayTitle, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        ShadowBar(0.52f, 5.dp, palette.textPrimary.copy(alpha = 0.68f))
                    }
                }
                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                    Icon(if (useLive && liveCtx.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = palette.onAccent, modifier = Modifier.size(10.dp))
                }
            }
        }
    }
}

@Composable
private fun LivePlaylistsMockup(
    layout: PlaylistsLayout,
    palette: StudioMockPalette,
    liveCtx: StudioLiveContext
) {
    val names = remember(liveCtx.playlistNames, liveCtx.hasLivePlaylists) {
        if (liveCtx.hasLivePlaylists) {
            val base = liveCtx.playlistNames.take(6)
            if (base.size >= 4) base else base + listOf("Favorites", "Late Drive", "Chill Mix", "Workout").take(4 - base.size)
        } else {
            emptyList()
        }
    }
    val useLive = names.isNotEmpty()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Playlists", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
            Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, null, tint = palette.onAccent, modifier = Modifier.size(12.dp))
            }
        }

        when (layout) {
            PlaylistsLayout.BENTO_GRID -> {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                    repeat(2) { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                            repeat(2) { c ->
                                val idx = r * 2 + c
                                val songArt = liveCtx.recentSongs.getOrNull(idx)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = palette.cardBg,
                                    border = BorderStroke(0.7.dp, palette.cardBorder),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                ) {
                                    Column(modifier = Modifier.padding(7.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))) {
                                            MockArtworkSurface(songArt, liveCtx.isShadowMode || songArt == null, palette, 8, Modifier.fillMaxSize(), 18.dp)
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        if (useLive) {
                                            Text(names.getOrElse(idx) { "Playlist ${idx + 1}" }, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${14 + idx * 6} tracks", fontSize = 7.sp, color = palette.textSecondary)
                                        } else {
                                            ShadowBar(0.78f, 5.5.dp, palette.textPrimary.copy(alpha = 0.68f))
                                            Spacer(Modifier.height(3.dp))
                                            ShadowBar(0.45f, 4.dp, palette.textSecondary.copy(alpha = 0.42f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            PlaylistsLayout.MAGAZINE_HERO -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(98.dp).clip(RoundedCornerShape(14.dp))
                ) {
                    MockArtworkSurface(liveCtx.song, !liveCtx.hasLiveSong, palette, 14, Modifier.fillMaxSize(), 26.dp)
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.68f)))))
                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("FEATURED", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
                        if (useLive) {
                            Text(names.first(), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            ShadowBar(0.65f, 7.dp, Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    repeat(3) { idx ->
                        Surface(shape = RoundedCornerShape(10.dp), color = palette.cardBg, border = BorderStroke(0.6.dp, palette.cardBorder), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                if (useLive) {
                                    Text(names.getOrElse(idx + 1) { "Mix ${idx + 1}" }, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                } else {
                                    Box(modifier = Modifier.weight(1f)) { ShadowBar(0.68f, 5.5.dp, palette.textPrimary.copy(alpha = 0.65f)) }
                                }
                                Icon(Icons.Default.PlayArrow, null, tint = palette.accent, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }

            PlaylistsLayout.COMPACT_LIST -> {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                    repeat(6) { idx ->
                        Surface(shape = RoundedCornerShape(9.dp), color = palette.cardBg, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(if (idx % 2 == 0) palette.accent.copy(alpha = 0.45f) else palette.artworkTop))
                                if (useLive && idx < names.size) {
                                    Text(names[idx], fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                } else {
                                    Box(modifier = Modifier.weight(1f)) { ShadowBar(0.62f, 5.dp, palette.textPrimary.copy(alpha = 0.62f)) }
                                }
                                Icon(Icons.Default.PlayArrow, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }

            PlaylistsLayout.GLOW_CARDS -> {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                    repeat(3) { idx ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.accent.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    if (useLive) {
                                        Text(names.getOrElse(idx) { "Curated Mix ${idx + 1}" }, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Curated Playlist", fontSize = 7.5.sp, color = palette.accent)
                                    } else {
                                        ShadowBar(0.68f, 6.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                                        ShadowBar(0.42f, 4.5.dp, palette.accent.copy(alpha = 0.6f))
                                    }
                                }
                                Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayArrow, null, tint = palette.onAccent, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveLyricsMockup(
    layout: LyricsLayout,
    palette: StudioMockPalette,
    liveCtx: StudioLiveContext
) {
    val hasLyrics = liveCtx.hasLiveLyrics
    val liveLines = liveCtx.lyricLines
    val song = liveCtx.song
    val useLiveSong = liveCtx.hasLiveSong

    when (layout) {
        LyricsLayout.CLASSIC_DEFAULT -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
                    Text("SYNCED LYRICS", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = palette.accent)
                    Icon(Icons.Default.Translate, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasLyrics) {
                        val line0 = liveLines.getOrNull(0)?.second.orEmpty()
                        val line1 = liveLines.getOrNull(1)?.second ?: line0
                        val line2 = liveLines.getOrNull(2)?.second.orEmpty()
                        val line3 = liveLines.getOrNull(3)?.second.orEmpty()
                        if (line0.isNotBlank()) Text(line0, fontSize = 9.sp, color = palette.textSecondary.copy(alpha = 0.45f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(line1, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (line2.isNotBlank()) Text(line2, fontSize = 9.sp, color = palette.textSecondary.copy(alpha = 0.55f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (line3.isNotBlank()) Text(line3, fontSize = 8.5.sp, color = palette.textSecondary.copy(alpha = 0.35f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        // Exact centered lyric line shadows
                        ShadowBar(0.68f, 6.dp, palette.textSecondary.copy(alpha = 0.35f))
                        ShadowBar(0.86f, 10.dp, palette.textPrimary.copy(alpha = 0.85f))
                        ShadowBar(0.72f, 6.dp, palette.textSecondary.copy(alpha = 0.42f))
                        ShadowBar(0.56f, 5.5.dp, palette.textSecondary.copy(alpha = 0.28f))
                    }
                }
                Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.7.dp, palette.cardBorder)) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.SkipPrevious, null, tint = palette.textPrimary, modifier = Modifier.size(13.dp))
                        Icon(Icons.Default.Pause, null, tint = palette.accent, modifier = Modifier.size(14.dp))
                        Icon(Icons.Default.SkipNext, null, tint = palette.textPrimary, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }

        LyricsLayout.FLUID_GLOW -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))) {
                        MockArtworkSurface(song, !useLiveSong, palette, 7, Modifier.fillMaxSize(), 12.dp)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (useLiveSong && song != null) {
                            Text(song.displayTitle, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.displayArtist, fontSize = 7.5.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            ShadowBar(0.65f, 6.dp, palette.textPrimary.copy(alpha = 0.72f))
                            ShadowBar(0.42f, 4.5.dp, palette.textSecondary.copy(alpha = 0.45f))
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasLyrics) {
                        val line0 = liveLines.getOrNull(0)?.second.orEmpty()
                        val line1 = liveLines.getOrNull(1)?.second ?: line0
                        val line2 = liveLines.getOrNull(2)?.second.orEmpty()
                        val line3 = liveLines.getOrNull(3)?.second.orEmpty()
                        if (line0.isNotBlank()) Text(line0, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary.copy(alpha = 0.42f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(line1, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (line2.isNotBlank()) Text(line2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = palette.textSecondary.copy(alpha = 0.58f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (line3.isNotBlank()) Text(line3, fontSize = 9.sp, color = palette.textSecondary.copy(alpha = 0.35f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        // Left-aligned expressive Fluid Glow line shadows
                        ShadowBar(0.62f, 6.dp, palette.textSecondary.copy(alpha = 0.35f))
                        ShadowBar(0.92f, 11.dp, palette.textPrimary.copy(alpha = 0.88f))
                        ShadowBar(0.74f, 11.dp, palette.textPrimary.copy(alpha = 0.85f))
                        ShadowBar(0.68f, 6.5.dp, palette.textSecondary.copy(alpha = 0.45f))
                        ShadowBar(0.50f, 5.5.dp, palette.textSecondary.copy(alpha = 0.28f))
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(999.dp), color = palette.accent.copy(alpha = 0.2f)) {
                        Text("0.0s", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = palette.accent, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Pause, null, tint = palette.onAccent, modifier = Modifier.size(14.dp))
                    }
                    Icon(Icons.Default.Translate, null, tint = palette.textSecondary, modifier = Modifier.size(14.dp))
                }
            }
        }

        LyricsLayout.EDITORIAL_TIMELINE -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("TIMELINE", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = palette.accent)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val rowCount = if (hasLyrics) liveLines.take(4).size else 4
                    repeat(rowCount) { idx ->
                        val active = idx == 1 || (rowCount == 1 && idx == 0)
                        val ts = if (hasLyrics) liveLines[idx].first else listOf("01:12", "01:18", "01:25", "01:32")[idx]
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = ts,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) palette.accent else palette.textSecondary.copy(alpha = 0.45f)
                            )
                            Box(
                                modifier = Modifier
                                    .padding(top = 3.dp)
                                    .size(if (active) 6.dp else 4.dp)
                                    .clip(CircleShape)
                                    .background(if (active) palette.accent else palette.textSecondary.copy(alpha = 0.35f))
                            )
                            if (hasLyrics) {
                                Text(
                                    text = liveLines[idx].second,
                                    fontSize = if (active) 11.sp else 8.5.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) palette.textPrimary else palette.textSecondary.copy(alpha = 0.5f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Box(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                                    ShadowBar(
                                        widthFraction = if (active) 0.88f else 0.66f,
                                        height = if (active) 8.dp else 5.5.dp,
                                        color = if (active) palette.textPrimary.copy(alpha = 0.82f) else palette.textSecondary.copy(alpha = 0.38f)
                                    )
                                }
                            }
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.16f))) {
                    Box(modifier = Modifier.fillMaxWidth(0.54f).fillMaxHeight().background(palette.accent))
                }
            }
        }
    }
}
