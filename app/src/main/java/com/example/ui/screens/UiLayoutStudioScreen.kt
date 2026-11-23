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
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
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
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.example.data.model.Lyrics
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.ui.components.AlbumArtView
import com.example.ui.components.PlaylistCover
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

@Immutable
data class StudioHomeLiveStats(
    val todayDurationMs: Long = 0L,
    val todayPlays: Int = 0,
    val todayUniqueTracks: Int = 0,
    val yesterdayDurationMs: Long = 0L,
    val yesterdayPlays: Int = 0,
    val yesterdayUniqueTracks: Int = 0,
    val weekDurationMs: Long = 0L,
    val weekPlays: Int = 0,
    val weekUniqueTracks: Int = 0,
    val monthDurationMs: Long = 0L,
    val monthPlays: Int = 0,
    val allTimeDurationMs: Long = 0L,
    val allTimePlays: Int = 0,
    val totalCatalogSongs: Int = 0,
    val userEmail: String? = null,
    val userAvatarUri: String? = null,
    val playbackPositionMs: Long = 0L
)

private data class StudioLiveContext(
    val song: Song?,
    val isPlaying: Boolean,
    val lyricLines: List<Pair<String, String>>, // (timestamp, text)
    val playlists: List<Playlist>,
    val playlistNames: List<String>,
    val recentSongs: List<Song>,
    val userDisplayName: String,
    val homeStats: StudioHomeLiveStats,
    val isShadowMode: Boolean
) {
    val hasLiveSong: Boolean get() = !isShadowMode && song != null
    val hasLiveLyrics: Boolean get() = !isShadowMode && lyricLines.isNotEmpty()
    val hasLivePlaylists: Boolean get() = !isShadowMode && (playlists.isNotEmpty() || playlistNames.isNotEmpty())
    val hasLiveRecents: Boolean get() = !isShadowMode && recentSongs.isNotEmpty()
}

/**
 * Dedicated full-screen Swipeable Live UI Layout Preview Studio.
 * Renders real currently playing artwork, track metadata, audio format, synced lyrics,
 * real listening telemetry, real playlist covers, and active Home Feed sections when
 * accessed from Settings — and gracefully falls back to exact-structure shadow previews
 * during Onboarding or when nothing is playing.
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
    livePlaylists: List<Playlist> = emptyList(),
    livePlaylistNames: List<String> = emptyList(),
    liveRecentSongs: List<Song> = emptyList(),
    liveUserDisplayName: String = "Music Lover",
    liveHomeStats: StudioHomeLiveStats = StudioHomeLiveStats(),
    isOnboardingShadowMode: Boolean = false,
    onSkipOnboarding: (() -> Unit)? = null,
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

    val liveCtx = remember(
        liveSong,
        liveIsPlaying,
        parsedLyricPreview,
        livePlaylists,
        livePlaylistNames,
        liveRecentSongs,
        liveUserDisplayName,
        liveHomeStats,
        isOnboardingShadowMode
    ) {
        StudioLiveContext(
            song = liveSong,
            isPlaying = liveIsPlaying,
            lyricLines = parsedLyricPreview,
            playlists = livePlaylists,
            playlistNames = if (livePlaylists.isNotEmpty()) livePlaylists.map { it.name } else livePlaylistNames,
            recentSongs = liveRecentSongs,
            userDisplayName = liveUserDisplayName.ifBlank { "Music Lover" },
            homeStats = liveHomeStats,
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

            if (isOnboardingShadowMode && onSkipOnboarding != null) {
                TextButton(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onSkipOnboarding()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("layout_studio_onboarding_skip_button")
                ) {
                    Text(
                        text = "Skip for now",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextSecondary
                    )
                }
            } else if (pendingChangeCount > 0) {
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
private fun MockPlaylistCoverSurface(
    playlist: Playlist?,
    isShadow: Boolean,
    palette: StudioMockPalette,
    cornerRadius: Int,
    modifier: Modifier = Modifier
) {
    if (!isShadow && playlist != null) {
        PlaylistCover(
            playlist = playlist,
            cornerRadius = cornerRadius,
            modifier = modifier
        )
    } else {
        // Exact 4-grid fallback collage shadow (never random unrelated song artwork)
        val cellShades = listOf(
            palette.artworkTop.copy(alpha = 0.85f),
            lerp(palette.artworkTop, palette.artworkBottom, 0.35f),
            lerp(palette.artworkTop, palette.artworkBottom, 0.65f),
            palette.artworkBottom.copy(alpha = 0.92f)
        )
        Box(modifier = modifier.clip(RoundedCornerShape(cornerRadius.dp))) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().background(cellShades[0]))
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().background(cellShades[1]))
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().background(cellShades[2]))
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().background(cellShades[3]))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.42f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun MockUserAvatar(
    avatarUri: String?,
    isShadow: Boolean,
    palette: StudioMockPalette,
    size: Dp,
    cornerRadius: Dp = 999.dp
) {
    val shape = if (cornerRadius >= 999.dp) CircleShape else RoundedCornerShape(cornerRadius)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(palette.accent.copy(alpha = 0.24f)),
        contentAlignment = Alignment.Center
    ) {
        if (!isShadow && !avatarUri.isNullOrBlank()) {
            AsyncImage(
                model = avatarUri,
                contentDescription = "User avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = palette.textPrimary,
                modifier = Modifier.size(size * 0.62f)
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
    return if (sec > 0L) String.format(Locale.US, "%d:%02d", sec / 60L, sec % 60L) else "0:00"
}

private fun formatCompactStudioStat(durationMs: Long): String {
    val totalMin = (durationMs / 60_000L).coerceAtLeast(0L)
    val hrs = totalMin / 60L
    val mins = totalMin % 60L
    return when {
        hrs > 0L && mins > 0L -> "${hrs}h ${mins}m"
        hrs > 0L -> "${hrs}h"
        else -> "${mins}m"
    }
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
    val isFav = useLive && song?.isFavorite == true

    when (layout) {
        NowPlayingLayout.MODERN_BENTO -> {
            // Exact Modern Bento screenshot structure: full-bleed upper artwork dissolving into velvety stage,
            // top-left frosted circle KeyboardArrowDown, bottom Title+Artist+(MoreVert)(Favorite), white waveform,
            // 3 borderless transport (FastRewind/Play/FastForward), optional volume bar, Headphones Phone speaker,
            // bottom bar: FormatListBulleted, Shuffle, split frosted pill [SpeakerGroup | Timer], Repeat, Lyrics
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.57f)
                ) {
                    MockArtworkSurface(
                        song = song,
                        isShadow = !useLive,
                        palette = palette,
                        cornerRadius = 0,
                        modifier = Modifier.fillMaxSize(),
                        fallbackIconSize = 42.dp
                    )
                    Box(
                        modifier = Modifier
                            .padding(10.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.36f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.48f)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, palette.bgBottom)))
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(5.5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (useLive && song != null) {
                                Text(song.displayTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.displayArtist, fontSize = 8.5.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                ShadowBar(0.76f, 8.dp, palette.textPrimary.copy(alpha = 0.72f))
                                ShadowBar(0.48f, 5.5.dp, palette.textSecondary.copy(alpha = 0.45f))
                            }
                            if (showAdvancedSongInfo) {
                                Text(
                                    text = previewFormatBadge(if (useLive) song else null),
                                    fontSize = 6.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.textSecondary.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Box(
                            modifier = Modifier.size(20.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.MoreVert, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp)) }
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier.size(20.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null,
                                tint = if (isFav) BentoAccentPinkText else palette.textPrimary,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        WaveformMiniPreviewCanvas(
                            styleId = waveformStyle,
                            accentColor = if (palette.isLight) palette.textPrimary else Color.White,
                            trackColor = palette.textPrimary.copy(alpha = 0.24f),
                            modifier = Modifier.fillMaxWidth().height(15.dp)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1:24", fontSize = 6.5.sp, color = palette.textSecondary)
                            Text(durationLabel, fontSize = 6.5.sp, color = palette.textSecondary)
                        }
                    }

                    // 3 borderless transport icons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FastRewind, null, tint = palette.textPrimary, modifier = Modifier.size(19.dp))
                        Icon(playIcon, null, tint = palette.textPrimary, modifier = Modifier.size(26.dp))
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

                    // Centered Headphones Phone speaker badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Headphones, null, tint = palette.textSecondary, modifier = Modifier.size(8.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Phone speaker", fontSize = 7.sp, color = palette.textSecondary)
                    }

                    // Bottom bar: FormatListBulleted, Shuffle | split frosted pill [SpeakerGroup | Timer] | Repeat, Lyrics
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                        Icon(Icons.Default.Shuffle, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = palette.textPrimary.copy(alpha = 0.13f),
                            border = BorderStroke(0.5.dp, palette.textPrimary.copy(alpha = 0.14f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.SpeakerGroup, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                                Box(modifier = Modifier.width(0.6.dp).height(9.dp).background(palette.textPrimary.copy(alpha = 0.25f)))
                                Icon(Icons.Default.Timer, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                            }
                        }
                        Icon(Icons.Default.Repeat, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                        Icon(Icons.Default.Lyrics, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }

        NowPlayingLayout.VINYL_TURNTABLE -> {
            // Exact Vinyl Turntable screenshot structure:
            // Top bar: Circle KeyboardArrowDown | center pill "• VINYL TURNTABLE • 33⅓ RPM" + "Phone speaker" | circles (QueueMusic, MoreVert)
            // Turntable Plinth card with 4 corner screws, spinning platter + tonearm + bottom-left red dot "QUARTZ LOCK"
            // Console card: Title + "Artist • Album" + circle Favorite + waveform + timestamps
            // Transport: Shuffle, circle SkipPrevious, accent circle Play/Pause, circle SkipNext, Repeat
            // Bottom 3 equal labeled pill buttons: Lyrics, Output, Sleep
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.cardBg).border(0.5.dp, palette.cardBorder, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder)) {
                            Text("• VINYL TURNTABLE • 33⅓ RPM", fontSize = 5.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = palette.accent, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Text("Phone speaker", fontSize = 6.sp, color = palette.textSecondary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.cardBg).border(0.5.dp, palette.cardBorder, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                        }
                        Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.cardBg).border(0.5.dp, palette.cardBorder, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MoreVert, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                        }
                    }
                }

                // Turntable Plinth Card with corner screws, platter, tonearm, QUARTZ LOCK
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.cardBg,
                    border = BorderStroke(0.7.dp, palette.cardBorder),
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                        // Corner screws + tonearm
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val pad = 3.dp.toPx()
                            val screwColor = palette.textSecondary.copy(alpha = 0.45f)
                            listOf(
                                Offset(pad, pad),
                                Offset(size.width - pad, pad),
                                Offset(pad, size.height - pad),
                                Offset(size.width - pad, size.height - pad)
                            ).forEach { drawCircle(screwColor, radius = 1.6.dp.toPx(), center = it) }

                            val pivot = Offset(size.width * 0.86f, size.height * 0.18f)
                            val tip = Offset(size.width * 0.64f, size.height * 0.62f)
                            drawCircle(color = Color(0xFFB8C0D0), radius = 4.dp.toPx(), center = pivot)
                            drawLine(color = Color(0xFFD5DCE8), start = pivot, end = tip, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        }

                        // Spinning Vinyl Platter
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(98.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(Color(0xFF282C38), Color(0xFF0A0B10))))
                                .border(1.5.dp, palette.accent.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                listOf(0.42f, 0.35f, 0.28f).forEach { rFrac ->
                                    drawCircle(Color.White.copy(alpha = 0.09f), radius = size.minDimension * rFrac, center = Offset(cx, cy), style = Stroke(width = 0.7.dp.toPx()))
                                }
                            }
                            Box(
                                modifier = Modifier.size(42.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                MockArtworkSurface(song, !useLive, palette, 999, Modifier.fillMaxSize(), 16.dp)
                                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF0A0B10)))
                            }
                        }

                        // Bottom-left red dot QUARTZ LOCK
                        Row(
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color(0xFFFF4D4F)))
                            Text("QUARTZ LOCK", fontSize = 5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = palette.textSecondary)
                        }
                    }
                }

                // Console Card: Title + Artist•Album + Favorite circle + waveform + timestamps
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.cardBg,
                    border = BorderStroke(0.6.dp, palette.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                                if (useLive && song != null) {
                                    Text(song.displayTitle, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${song.displayArtist} • ${song.displayAlbum}", fontSize = 7.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    ShadowBar(0.72f, 6.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                                    ShadowBar(0.50f, 4.5.dp, palette.textSecondary.copy(alpha = 0.45f))
                                }
                            }
                            Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isFav) BentoAccentPinkText else palette.textPrimary, modifier = Modifier.size(10.dp))
                            }
                        }
                        WaveformMiniPreviewCanvas(
                            styleId = waveformStyle,
                            accentColor = palette.accent,
                            trackColor = palette.textPrimary.copy(alpha = 0.22f),
                            modifier = Modifier.fillMaxWidth().height(13.dp)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1:24", fontSize = 6.sp, fontFamily = FontFamily.Monospace, color = palette.textSecondary)
                            Text(durationLabel, fontSize = 6.sp, fontFamily = FontFamily.Monospace, color = palette.textSecondary)
                        }
                    }
                }

                // Transport: Shuffle, circle SkipPrevious, accent circle Play/Pause, circle SkipNext, Repeat
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shuffle, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                    Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(palette.cardBg).border(0.5.dp, palette.cardBorder, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SkipPrevious, null, tint = palette.textPrimary, modifier = Modifier.size(13.dp))
                    }
                    Box(modifier = Modifier.size(30.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                        Icon(playIcon, null, tint = palette.onAccent, modifier = Modifier.size(16.dp))
                    }
                    Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(palette.cardBg).border(0.5.dp, palette.cardBorder, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SkipNext, null, tint = palette.textPrimary, modifier = Modifier.size(13.dp))
                    }
                    Icon(Icons.Default.Repeat, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                }

                // Bottom 3 equal labeled buttons: Lyrics, Output, Sleep
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(
                        Icons.Default.Lyrics to "Lyrics",
                        Icons.Default.SpeakerGroup to "Output",
                        Icons.Default.Bedtime to "Sleep"
                    ).forEach { (icon, lbl) ->
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = palette.cardBg,
                            border = BorderStroke(0.5.dp, palette.cardBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, null, tint = palette.textSecondary, modifier = Modifier.size(9.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(lbl, fontSize = 6.5.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary)
                            }
                        }
                    }
                }
            }
        }

        NowPlayingLayout.MINIMAL_GLASS -> {
            // Exact Minimalist Glass screenshot structure:
            // Top bar: KeyboardArrowDown | "MINIMAL GLASS" | SpeakerGroup + MoreVert
            // Frosted padded outer card containing square rounded album art
            // Centered Title + Artist
            // Waveform + timestamps
            // Frosted glass pill transport bar (Shuffle, SkipPrevious, accent circle Play/Pause, SkipNext, Repeat)
            // Bottom 4 icons: Favorite, Bedtime, QueueMusic, Lyrics
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(palette.artworkTop.copy(alpha = 0.65f), palette.bgBottom)))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
                    Text("MINIMAL GLASS", fontSize = 6.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = palette.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SpeakerGroup, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                        Icon(Icons.Default.MoreVert, null, tint = palette.textPrimary, modifier = Modifier.size(12.dp))
                    }
                }

                // Frosted padded outer frame containing square rounded album art
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = palette.textPrimary.copy(alpha = 0.10f),
                    border = BorderStroke(0.9.dp, palette.textPrimary.copy(alpha = 0.24f)),
                    modifier = Modifier.size(122.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(13.dp))) {
                        MockArtworkSurface(song, !useLive, palette, 13, Modifier.fillMaxSize(), 34.dp)
                    }
                }

                // Centered Title + Artist
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (useLive && song != null) {
                        Text(song.displayTitle, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.displayArtist, fontSize = 8.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        ShadowBar(0.66f, 7.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                        ShadowBar(0.42f, 5.dp, palette.textSecondary.copy(alpha = 0.45f))
                    }
                    if (showAdvancedSongInfo) {
                        Text(previewFormatBadge(if (useLive) song else null), fontSize = 6.sp, fontFamily = FontFamily.Monospace, color = palette.textSecondary)
                    }
                }

                // Waveform + timestamps
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    WaveformMiniPreviewCanvas(
                        styleId = waveformStyle,
                        accentColor = palette.accent,
                        trackColor = palette.textPrimary.copy(alpha = 0.22f),
                        modifier = Modifier.fillMaxWidth().height(14.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1:24", fontSize = 6.5.sp, color = palette.textSecondary)
                        Text(durationLabel, fontSize = 6.5.sp, color = palette.textSecondary)
                    }
                }

                // Frosted glass pill transport bar
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = palette.textPrimary.copy(alpha = 0.12f),
                    border = BorderStroke(0.7.dp, palette.textPrimary.copy(alpha = 0.22f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Shuffle, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                        Icon(Icons.Default.SkipPrevious, null, tint = palette.textPrimary, modifier = Modifier.size(15.dp))
                        Box(modifier = Modifier.size(26.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                            Icon(playIcon, null, tint = palette.onAccent, modifier = Modifier.size(15.dp))
                        }
                        Icon(Icons.Default.SkipNext, null, tint = palette.textPrimary, modifier = Modifier.size(15.dp))
                        Icon(Icons.Default.Repeat, null, tint = palette.textSecondary, modifier = Modifier.size(11.dp))
                    }
                }

                // Bottom 4 icons: Favorite, Bedtime, QueueMusic, Lyrics
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isFav) BentoAccentPinkText else palette.textSecondary, modifier = Modifier.size(12.dp))
                    Icon(Icons.Default.Bedtime, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                    Icon(Icons.Default.Lyrics, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                }
            }
        }

        NowPlayingLayout.NEON_WAVEFORM -> {
            // Exact Neon Studio screenshot structure:
            // Full-bleed dual-tone ambient gradient with crisp white ink, left KeyboardArrowDown + center drag handle pill,
            // large square rounded art, white waveform + timestamps, centered Title + Artist,
            // 5-icon borderless transport (Shuffle, FastRewind, Play/Pause, FastForward, Favorite),
            // bottom bar: MoreHoriz | frosted pill (Podcasts, Repeat, Bedtime, QueueMusic) | Lyrics
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(palette.artworkTop, palette.bgBottom)))
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.align(Alignment.CenterStart).size(14.dp))
                    Box(modifier = Modifier.align(Alignment.Center).width(26.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                ) {
                    MockArtworkSurface(song, !useLive, palette, 16, Modifier.fillMaxSize(), 38.dp)
                }

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    WaveformMiniPreviewCanvas(
                        styleId = waveformStyle,
                        accentColor = Color.White,
                        trackColor = Color.White.copy(alpha = 0.28f),
                        modifier = Modifier.fillMaxWidth().height(15.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1:24", fontSize = 6.5.sp, color = Color.White.copy(alpha = 0.72f))
                        Text(durationLabel, fontSize = 6.5.sp, color = Color.White.copy(alpha = 0.72f))
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (useLive && song != null) {
                        Text(song.displayTitle, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.displayArtist, fontSize = 8.sp, color = Color.White.copy(alpha = 0.76f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        ShadowBar(0.64f, 7.5.dp, Color.White.copy(alpha = 0.78f))
                        ShadowBar(0.42f, 5.dp, Color.White.copy(alpha = 0.46f))
                    }
                    if (showAdvancedSongInfo) {
                        Text(previewFormatBadge(if (useLive) song else null), fontSize = 6.sp, fontFamily = FontFamily.Monospace, color = Color.White.copy(alpha = 0.72f))
                    }
                }

                // 5-icon borderless transport: Shuffle, FastRewind, Play/Pause, FastForward, Favorite
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shuffle, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(12.dp))
                    Icon(Icons.Default.FastRewind, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Icon(playIcon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isFav) BentoAccentPinkText else Color.White, modifier = Modifier.size(12.dp))
                }

                // Bottom bar: MoreHoriz | frosted pill (Podcasts, Repeat, Bedtime, QueueMusic) | Lyrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MoreHoriz, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(13.dp))
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.22f))
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Podcasts, null, tint = Color.White, modifier = Modifier.size(10.dp))
                            Icon(Icons.Default.Repeat, null, tint = Color.White, modifier = Modifier.size(10.dp))
                            Icon(Icons.Default.Bedtime, null, tint = Color.White, modifier = Modifier.size(10.dp))
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color.White, modifier = Modifier.size(10.dp))
                        }
                    }
                    Icon(Icons.Default.Lyrics, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(12.dp))
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
    val playIcon = if (useLive && liveCtx.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
    val isFav = useLive && song?.isFavorite == true
    val greetingName = if (!liveCtx.isShadowMode && liveCtx.userDisplayName.isNotBlank()) liveCtx.userDisplayName else "Listener"
    val stats = liveCtx.homeStats
    val isLiveStats = !liveCtx.isShadowMode
    val trackCountLabel = if (stats.totalCatalogSongs > 0) "${stats.totalCatalogSongs}" else "${liveCtx.recentSongs.size}"
    val todayDurStr = formatCompactStudioStat(stats.todayDurationMs)
    val yesterdayDurStr = formatCompactStudioStat(stats.yesterdayDurationMs)
    val weekDurStr = formatCompactStudioStat(stats.weekDurationMs)
    val monthDurStr = formatCompactStudioStat(stats.monthDurationMs)
    val allTimeDurStr = formatCompactStudioStat(stats.allTimeDurationMs)
    val songDurationMs = (song?.durationMs ?: 260_000L).coerceAtLeast(1_000L)
    val posFraction = if (useLive && stats.playbackPositionMs > 0L) {
        (stats.playbackPositionMs.toFloat() / songDurationMs.toFloat()).coerceIn(0.08f, 0.94f)
    } else {
        0.58f
    }
    val posLabel = if (useLive && stats.playbackPositionMs > 0L) {
        formatPreviewDuration(stats.playbackPositionMs)
    } else {
        formatPreviewDuration((songDurationMs * posFraction).toLong())
    }
    val durLabel = formatPreviewDuration(songDurationMs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        when (layout) {
            HomeScreenLayout.HERO_SPOTLIGHT -> {
                // Exact Hero Spotlight screenshot structure:
                // Header: rounded-square Menu + "Welcome, <Name>" | right dark pill capsule (Avatar, Search, Refresh, filled accent circle Settings)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(palette.cardBg)
                                .border(0.5.dp, palette.cardBorder, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Menu, null, tint = palette.textPrimary, modifier = Modifier.size(9.dp))
                        }
                        if (!liveCtx.isShadowMode) {
                            Text(
                                "Welcome, $greetingName",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            ShadowBar(0.52f, 5.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.cardBg,
                        border = BorderStroke(0.5.dp, palette.cardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 3.5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                        ) {
                            MockUserAvatar(stats.userAvatarUri, liveCtx.isShadowMode, palette, 12.dp)
                            Icon(Icons.Default.Search, null, tint = palette.textPrimary, modifier = Modifier.size(8.dp))
                            Icon(Icons.Default.Refresh, null, tint = palette.textPrimary, modifier = Modifier.size(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(CircleShape)
                                    .background(palette.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Settings, null, tint = palette.onAccent, modifier = Modifier.size(8.dp))
                            }
                        }
                    }
                }

                // Hero Continue Listening card: circular art w/ accent ring, Title / accent Artist / muted Album, top-right circles MoreVert + Heart, white squiggly waveform, Play + Shuffle Mix pills
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = palette.cardBg,
                    border = BorderStroke(0.8.dp, palette.accent.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(30.dp).clip(CircleShape).border(1.1.dp, palette.accent, CircleShape)) {
                                MockArtworkSurface(song, !useLive, palette, 999, Modifier.fillMaxSize(), 13.dp)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                if (useLive && song != null) {
                                    Text(song.displayTitle, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.displayArtist, fontSize = 6.5.sp, fontWeight = FontWeight.Medium, color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.displayAlbum, fontSize = 5.5.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    ShadowBar(0.78f, 5.dp, palette.textPrimary.copy(alpha = 0.75f))
                                    ShadowBar(0.54f, 3.8.dp, palette.accent.copy(alpha = 0.7f))
                                    ShadowBar(0.38f, 3.dp, palette.textSecondary.copy(alpha = 0.42f))
                                }
                            }
                            Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MoreVert, null, tint = palette.textPrimary, modifier = Modifier.size(8.dp))
                            }
                            Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isFav) BentoAccentPinkText else palette.textPrimary, modifier = Modifier.size(8.dp))
                            }
                        }
                        WaveformMiniPreviewCanvas(
                            styleId = waveformStyle,
                            accentColor = if (palette.isLight) palette.accent else Color.White,
                            trackColor = palette.textPrimary.copy(alpha = 0.22f),
                            modifier = Modifier.fillMaxWidth().height(10.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(
                                modifier = Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(999.dp)).background(palette.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(playIcon, null, tint = palette.onAccent, modifier = Modifier.size(8.5.dp))
                                    Text(if (useLive && liveCtx.isPlaying) "Pause" else "Play", fontSize = 6.5.sp, fontWeight = FontWeight.Bold, color = palette.onAccent)
                                }
                            }
                            Box(
                                modifier = Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(999.dp)).border(0.6.dp, palette.cardBorder, RoundedCornerShape(999.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.Shuffle, null, tint = palette.textPrimary, modifier = Modifier.size(8.dp))
                                    Text("Shuffle Mix", fontSize = 6.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                                }
                            }
                        }
                    }
                }

                // | Quick Actions: Shuffle Entire Catalog capsule + subtitle + 3 outlined pills (Library, Playlists, Favorites)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                        Box(modifier = Modifier.width(1.8.dp).height(7.5.dp).clip(CircleShape).background(palette.accent))
                        Text("Quick Actions", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    }
                    Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.6.dp, palette.cardBorder), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Shuffle, null, tint = palette.onAccent, modifier = Modifier.size(9.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Shuffle Entire Catalog", fontSize = 6.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1)
                                Text(
                                    if (isLiveStats) "$trackCountLabel tracks ready for instant mix" else "Instant catalog shuffle",
                                    fontSize = 5.sp,
                                    color = palette.textSecondary,
                                    maxLines = 1
                                )
                            }
                            Icon(Icons.Default.PlayArrow, null, tint = palette.accent, modifier = Modifier.size(10.dp))
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                        listOf(
                            Icons.Default.LibraryMusic to "Library",
                            Icons.AutoMirrored.Filled.QueueMusic to "Playlists",
                            Icons.Default.Favorite to "Favorites"
                        ).forEach { (ic, lbl) ->
                            Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.weight(1f)) {
                                Row(modifier = Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(ic, null, tint = palette.accent, modifier = Modifier.size(7.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(lbl, fontSize = 5.8.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                // | Listening Statistics + "view in detail →": TODAY & YESTERDAY rounded Bento cards with pill headers & plays/tracks chips
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                            Box(modifier = Modifier.width(1.8.dp).height(7.5.dp).clip(CircleShape).background(palette.accent))
                            Text("Listening Statistics", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder)) {
                            Text("view in detail →", fontSize = 5.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp))
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.5.dp)) {
                        listOf(
                            Triple("TODAY", todayDurStr, "${stats.todayPlays} plays • ${stats.todayUniqueTracks} tracks"),
                            Triple("YESTERDAY", yesterdayDurStr, "${stats.yesterdayPlays} plays • ${stats.yesterdayUniqueTracks} tracks")
                        ).forEachIndexed { idx, (label, dur, sub) ->
                            Surface(shape = RoundedCornerShape(10.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = RoundedCornerShape(999.dp), color = palette.textPrimary.copy(alpha = 0.08f)) {
                                            Text(label, fontSize = 4.8.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                        if (idx == 0) {
                                            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.GraphicEq, null, tint = palette.accent, modifier = Modifier.size(7.dp))
                                            }
                                        }
                                    }
                                    if (isLiveStats) {
                                        Text(dur, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, maxLines = 1)
                                        Surface(shape = RoundedCornerShape(999.dp), color = palette.textPrimary.copy(alpha = 0.06f)) {
                                            Text(sub, fontSize = 4.8.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                    } else {
                                        ShadowBar(0.52f, 6.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                                        ShadowBar(0.76f, 4.dp, palette.textSecondary.copy(alpha = 0.42f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HomeScreenLayout.BENTO_DYNAMIC -> {
                // Exact Bento Mosaic screenshot structure:
                // Header: circle Menu + "Welcome, <Name>" + email | 5 circles (Avatar, Search, Favorite, Refresh, accent Settings)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.size(15.dp).clip(CircleShape).background(palette.cardBg), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Menu, null, tint = palette.textPrimary, modifier = Modifier.size(8.5.dp))
                        }
                        Column {
                            Text(
                                if (!liveCtx.isShadowMode) "Welcome, $greetingName" else "Welcome, OffGrid",
                                fontSize = 7.2.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (!liveCtx.isShadowMode && !stats.userEmail.isNullOrBlank()) stats.userEmail else "OffGrid Music",
                                fontSize = 5.sp,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
                        MockUserAvatar(stats.userAvatarUri, liveCtx.isShadowMode, palette, 13.dp)
                        listOf(Icons.Default.Search, Icons.Default.Favorite, Icons.Default.Refresh).forEach { ic ->
                            Box(modifier = Modifier.size(13.dp).clip(CircleShape).background(palette.cardBg), contentAlignment = Alignment.Center) {
                                Icon(ic, null, tint = if (ic == Icons.Default.Favorite) palette.accent else palette.textPrimary, modifier = Modifier.size(7.5.dp))
                            }
                        }
                        Box(modifier = Modifier.size(13.dp).clip(CircleShape).background(palette.accent.copy(alpha = 0.25f)).border(0.5.dp, palette.accent.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Settings, null, tint = palette.accent, modifier = Modifier.size(7.5.dp))
                        }
                    }
                }

                // ● Continue Listening: Asymmetric 2-card row! Left wide card (art + RESUME TRACK + title/artist + bar + timestamps), Right tall card (Play icon + PLAY)
                Column(verticalArrangement = Arrangement.spacedBy(3.5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                        Box(modifier = Modifier.size(4.5.dp).clip(CircleShape).background(palette.accent))
                        Text("Continue Listening", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    }
                    Row(modifier = Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = palette.cardBg,
                            border = BorderStroke(0.6.dp, palette.cardBorder),
                            modifier = Modifier.weight(2.25f).fillMaxHeight()
                        ) {
                            Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Box(modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))) {
                                        MockArtworkSurface(song, !useLive, palette, 5, Modifier.fillMaxSize(), 10.dp)
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text("RESUME TRACK", fontSize = 4.6.sp, fontWeight = FontWeight.Bold, color = palette.accent)
                                        if (useLive && song != null) {
                                            Text(song.displayTitle, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(song.displayArtist, fontSize = 5.3.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        } else {
                                            ShadowBar(0.75f, 4.dp, palette.textPrimary.copy(alpha = 0.72f))
                                            ShadowBar(0.45f, 3.dp, palette.textSecondary.copy(alpha = 0.45f))
                                        }
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.16f))) {
                                        Box(modifier = Modifier.fillMaxWidth(posFraction).fillMaxHeight().clip(CircleShape).background(palette.accent))
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(if (useLive) posLabel else "1:42", fontSize = 4.5.sp, color = palette.textSecondary)
                                        Text(if (useLive) durLabel else "3:58", fontSize = 4.5.sp, color = palette.textSecondary)
                                    }
                                }
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = palette.cardBg,
                            border = BorderStroke(0.6.dp, palette.accent.copy(alpha = 0.45f)),
                            modifier = Modifier.weight(0.85f).fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(playIcon, null, tint = palette.accent, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (useLive && liveCtx.isPlaying) "PAUSE" else "PLAY",
                                    fontSize = 5.2.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary
                                )
                            }
                        }
                    }
                }

                // ● Quick Actions: Full-width Shuffle All Tracks card + 3 square dark cards (Library, Playlists, Favorites)
                Column(verticalArrangement = Arrangement.spacedBy(3.5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                        Box(modifier = Modifier.size(4.5.dp).clip(CircleShape).background(palette.accent))
                        Text("Quick Actions", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    }
                    Surface(shape = RoundedCornerShape(9.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.5.dp)).background(palette.textPrimary.copy(alpha = 0.09f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Shuffle, null, tint = palette.accent, modifier = Modifier.size(9.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Shuffle All Tracks", fontSize = 6.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1)
                                Text(
                                    if (isLiveStats) "$trackCountLabel songs in library" else "Library mix",
                                    fontSize = 5.sp,
                                    color = palette.textSecondary,
                                    maxLines = 1
                                )
                            }
                            Box(modifier = Modifier.size(15.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.09f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, null, tint = palette.accent, modifier = Modifier.size(9.dp))
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            Icons.Default.LibraryMusic to "Library",
                            Icons.AutoMirrored.Filled.QueueMusic to "Playlists",
                            Icons.Default.Favorite to "Favorites"
                        ).forEach { (ic, lbl) ->
                            Surface(shape = RoundedCornerShape(9.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.weight(1f).height(34.dp)) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(palette.textPrimary.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                        Icon(ic, null, tint = palette.accent, modifier = Modifier.size(8.dp))
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(lbl, fontSize = 5.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                // ● Listening Statistics + "view in detail →": 2 large cards (TODAY, THIS WEEK) + 3 compact cards (YESTERDAY, 30 DAYS, ALL TIME)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.5.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                            Box(modifier = Modifier.size(4.5.dp).clip(CircleShape).background(palette.accent))
                            Text("Listening Statistics", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder)) {
                            Text("view in detail →", fontSize = 5.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp))
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(1.2f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            Triple("TODAY", todayDurStr, "${stats.todayPlays} plays • ${stats.todayUniqueTracks} tracks"),
                            Triple("THIS WEEK", weekDurStr, "${stats.weekPlays} plays • ${stats.weekUniqueTracks} tracks")
                        ).forEachIndexed { idx, (lbl, dur, sub) ->
                            Surface(shape = RoundedCornerShape(9.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Column(modifier = Modifier.padding(5.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(lbl, fontSize = 4.8.sp, fontWeight = FontWeight.Bold, color = palette.accent)
                                        Icon(if (idx == 0) Icons.Default.CalendarToday else Icons.Default.TrendingUp, null, tint = palette.accent, modifier = Modifier.size(7.dp))
                                    }
                                    if (isLiveStats) {
                                        Text(dur, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, maxLines = 1)
                                        Text(sub, fontSize = 4.6.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    } else {
                                        ShadowBar(0.55f, 5.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                                        ShadowBar(0.75f, 3.2.dp, palette.textSecondary.copy(alpha = 0.42f))
                                    }
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(0.75f), horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                        listOf(
                            "YESTERDAY" to yesterdayDurStr,
                            "30 DAYS" to monthDurStr,
                            "ALL TIME" to allTimeDurStr
                        ).forEach { (lbl, dur) ->
                            Surface(shape = RoundedCornerShape(7.dp), color = palette.cardBg, border = BorderStroke(0.45.dp, palette.cardBorder), modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Column(modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 3.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Text(lbl, fontSize = 4.3.sp, fontWeight = FontWeight.Bold, color = palette.textSecondary, maxLines = 1)
                                    if (isLiveStats) {
                                        Text(dur, fontSize = 6.5.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, maxLines = 1)
                                    } else {
                                        ShadowBar(0.6f, 4.dp, palette.textPrimary.copy(alpha = 0.68f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HomeScreenLayout.MINIMAL_FEED -> {
                // Exact Minimal Stream screenshot structure:
                // Header: Plain Menu icon | "MINIMAL STREAM" (tiny accent) + user name (light large) | Avatar + plain Search, Shuffle, Settings
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Menu, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                        Column {
                            Text("MINIMAL STREAM", fontSize = 4.8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, color = palette.accent)
                            Text(
                                if (!liveCtx.isShadowMode) greetingName else "OffGrid",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Normal,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.5.dp), verticalAlignment = Alignment.CenterVertically) {
                        MockUserAvatar(stats.userAvatarUri, liveCtx.isShadowMode, palette, 13.dp)
                        Icon(Icons.Default.Search, null, tint = palette.textPrimary, modifier = Modifier.size(9.5.dp))
                        Icon(Icons.Default.Shuffle, null, tint = palette.textPrimary, modifier = Modifier.size(9.5.dp))
                        Icon(Icons.Default.Settings, null, tint = palette.textPrimary, modifier = Modifier.size(9.5.dp))
                    }
                }

                // Continue Listening Capsule Pill + thin progress line underneath
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.6.dp, palette.cardBorder), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.5.dp)) {
                            Box(modifier = Modifier.size(22.dp).clip(CircleShape)) {
                                MockArtworkSurface(song, !useLive, palette, 999, Modifier.fillMaxSize(), 10.dp)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("CONTINUE LISTENING", fontSize = 4.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = palette.accent)
                                if (useLive && song != null) {
                                    Text(song.displayTitle, fontSize = 7.2.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.displayArtist, fontSize = 5.3.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    ShadowBar(0.72f, 4.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                                    ShadowBar(0.48f, 3.2.dp, palette.textSecondary.copy(alpha = 0.42f))
                                }
                            }
                            Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isFav) BentoAccentPinkText else palette.textPrimary, modifier = Modifier.size(9.dp))
                            Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                                Icon(playIcon, null, tint = palette.onAccent, modifier = Modifier.size(10.dp))
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(1.6.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.14f))) {
                        Box(modifier = Modifier.fillMaxWidth(posFraction).fillMaxHeight().clip(CircleShape).background(palette.accent))
                    }
                }

                // QUICK ACTIONS: accent pill Shuffle All (N) + outlined Library + outlined Playlists
                Column(verticalArrangement = Arrangement.spacedBy(3.5.dp)) {
                    Text("QUICK ACTIONS", fontSize = 5.2.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = palette.accent)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.accent) {
                            Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                                Icon(Icons.Default.Shuffle, null, tint = palette.onAccent, modifier = Modifier.size(7.dp))
                                Text(
                                    if (isLiveStats) "Shuffle All ($trackCountLabel)" else "Shuffle All",
                                    fontSize = 5.6.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.onAccent,
                                    maxLines = 1
                                )
                            }
                        }
                        listOf(Icons.Default.LibraryMusic to "Library", Icons.AutoMirrored.Filled.QueueMusic to "Playlists").forEach { (ic, lbl) ->
                            Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder)) {
                                Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                                    Icon(ic, null, tint = palette.accent, modifier = Modifier.size(7.dp))
                                    Text(lbl, fontSize = 5.6.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                // LISTENING STATISTICS + "view in detail →": Frameless columns with thin left vertical accent bar (TODAY | YESTERDAY | THIS WEEK)
                Column(verticalArrangement = Arrangement.spacedBy(3.5.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("LISTENING STATISTICS", fontSize = 5.2.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = palette.accent)
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder)) {
                            Text("view in detail →", fontSize = 4.8.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(
                            Triple("TODAY", todayDurStr, "${stats.todayPlays} plays • ${stats.todayUniqueTracks} tracks"),
                            Triple("YESTERDAY", yesterdayDurStr, "${stats.yesterdayPlays} plays • ${stats.yesterdayUniqueTracks} tracks"),
                            Triple("THIS WEEK", weekDurStr, "${stats.weekPlays} plays")
                        ).forEach { (lbl, dur, sub) ->
                            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.width(1.2.dp).height(24.dp).background(palette.accent.copy(alpha = 0.85f)))
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(lbl, fontSize = 4.5.sp, fontWeight = FontWeight.Bold, color = palette.textSecondary, maxLines = 1)
                                    if (isLiveStats) {
                                        Text(dur, fontSize = 7.8.sp, fontWeight = FontWeight.Light, color = palette.textPrimary, maxLines = 1)
                                        Text(sub, fontSize = 4.2.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    } else {
                                        ShadowBar(0.65f, 5.dp, palette.textPrimary.copy(alpha = 0.68f))
                                        ShadowBar(0.8f, 3.dp, palette.textSecondary.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                    }
                }

                // RECENTLY PLAYED + "See all →": Circular artworks with bottom-left play count badge (▶ N) + centered Title & Artist
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.5.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("RECENTLY PLAYED", fontSize = 5.2.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = palette.accent)
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder)) {
                            Text("See all →", fontSize = 4.8.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        repeat(3) { idx ->
                            val item = if (liveCtx.hasLiveRecents) liveCtx.recentSongs.getOrNull(idx) else null
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Box(modifier = Modifier.size(34.dp)) {
                                    Box(modifier = Modifier.fillMaxSize().clip(CircleShape)) {
                                        MockArtworkSurface(item, item == null, palette, 999, Modifier.fillMaxSize(), 12.dp)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = Color.Black.copy(alpha = 0.72f),
                                        modifier = Modifier.align(Alignment.BottomStart).padding(1.dp)
                                    ) {
                                        Text(
                                            text = "▶ ${item?.playCount?.coerceAtLeast(1) ?: (idx + 1)}",
                                            fontSize = 4.2.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp)
                                        )
                                    }
                                }
                                if (item != null) {
                                    Text(item.displayTitle, fontSize = 5.4.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                    Text(item.displayArtist, fontSize = 4.4.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                } else {
                                    ShadowBar(0.75f, 3.5.dp, palette.textPrimary.copy(alpha = 0.65f))
                                    ShadowBar(0.5f, 2.8.dp, palette.textSecondary.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }
            }

            HomeScreenLayout.STUDIO_DASHBOARD -> {
                // Exact Studio Deck screenshot structure:
                // Header: rounded-square Menu + dot "STUDIO DECK" + bold name | 4 rounded-square buttons (Avatar, Search, Refresh, accent-tinted GraphicEq)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(15.dp).clip(RoundedCornerShape(4.5.dp)).background(palette.cardBg).border(0.5.dp, palette.cardBorder, RoundedCornerShape(4.5.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Menu, null, tint = palette.textPrimary, modifier = Modifier.size(8.5.dp))
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                                Box(modifier = Modifier.size(3.5.dp).clip(CircleShape).background(palette.accent))
                                Text("STUDIO DECK", fontSize = 4.8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = palette.accent)
                            }
                            Text(if (!liveCtx.isShadowMode) greetingName else "Console", fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        MockUserAvatar(stats.userAvatarUri, liveCtx.isShadowMode, palette, 14.dp, cornerRadius = 4.dp)
                        listOf(Icons.Default.Search, Icons.Default.Refresh).forEach { ic ->
                            Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(palette.cardBg).border(0.5.dp, palette.cardBorder, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                                Icon(ic, null, tint = palette.textPrimary, modifier = Modifier.size(8.dp))
                            }
                        }
                        Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(palette.accent.copy(alpha = 0.25f)).border(0.6.dp, palette.accent.copy(alpha = 0.65f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.GraphicEq, null, tint = palette.accent, modifier = Modifier.size(8.dp))
                        }
                    }
                }

                // DECK A // ON AIR or STANDBY card with rounded-square art, right rounded-square accent Play key, bottom thin progress bar w/ dot
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = palette.cardBg,
                    border = BorderStroke(0.7.dp, palette.accent.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(6.5.dp), verticalArrangement = Arrangement.spacedBy(4.5.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(modifier = Modifier.size(3.5.dp).clip(CircleShape).background(palette.accent))
                                Text(
                                    if (useLive && liveCtx.isPlaying) "DECK A // ON AIR" else "DECK A // STANDBY",
                                    fontSize = 5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.accent
                                )
                            }
                            Box(modifier = Modifier.size(13.dp).clip(RoundedCornerShape(3.5.dp)).background(palette.textPrimary.copy(alpha = 0.08f)).border(0.4.dp, palette.cardBorder, RoundedCornerShape(3.5.dp)), contentAlignment = Alignment.Center) {
                                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isFav) BentoAccentPinkText else palette.textPrimary, modifier = Modifier.size(7.5.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.5.dp)) {
                            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(5.dp))) {
                                MockArtworkSurface(song, !useLive, palette, 5, Modifier.fillMaxSize(), 11.dp)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                                if (useLive && song != null) {
                                    Text(song.displayTitle, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.displayArtist, fontSize = 5.5.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    ShadowBar(0.75f, 4.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                                    ShadowBar(0.45f, 3.2.dp, palette.textSecondary.copy(alpha = 0.45f))
                                }
                            }
                            Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(palette.accent), contentAlignment = Alignment.Center) {
                                Icon(playIcon, null, tint = palette.onAccent, modifier = Modifier.size(11.dp))
                            }
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.16f))) {
                            Box(modifier = Modifier.fillMaxWidth(posFraction).fillMaxHeight().clip(CircleShape).background(palette.accent))
                        }
                    }
                }

                // CH // LAUNCH MATRIX + Quick Actions: 4-column vertical dark cards (Shuffle, Library, Playlists, Favorites)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("CH // LAUNCH MATRIX", fontSize = 4.6.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = palette.accent)
                    Text("Quick Actions", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf(
                            Triple(Icons.Default.Shuffle, "Shuffle", if (isLiveStats) "$trackCountLabel trk" else "All"),
                            Triple(Icons.Default.LibraryMusic, "Library", "Catalog"),
                            Triple(Icons.AutoMirrored.Filled.QueueMusic, "Playlists", "Mixes"),
                            Triple(Icons.Default.Favorite, "Favorites", "Saved")
                        ).forEach { (ic, title, sub) ->
                            Surface(shape = RoundedCornerShape(7.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.padding(4.5.dp), verticalArrangement = Arrangement.spacedBy(2.5.dp)) {
                                    Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(3.5.dp)).background(palette.textPrimary.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                        Icon(ic, null, tint = palette.accent, modifier = Modifier.size(7.dp))
                                    }
                                    Text(title, fontSize = 5.3.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1)
                                    Text(sub, fontSize = 4.2.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // CH // AUDIO TELEMETRY + Listening Statistics: 3-column split card (TODAY | YEST | 7 DAYS) + divider + footer (30D ... | TOTAL ...)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text("CH // AUDIO TELEMETRY", fontSize = 4.6.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = palette.accent)
                            Text("Listening Statistics", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder)) {
                            Text("view in detail →", fontSize = 4.6.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp))
                        }
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(5.5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    Triple("TODAY", todayDurStr, "${stats.todayPlays} TRK"),
                                    Triple("YEST", yesterdayDurStr, "${stats.yesterdayPlays} TRK"),
                                    Triple("7 DAYS", weekDurStr, "${stats.weekPlays} TRK")
                                ).forEachIndexed { idx, (lbl, dur, sub) ->
                                    if (idx > 0) {
                                        Box(modifier = Modifier.width(0.5.dp).height(22.dp).background(palette.cardBorder))
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(lbl, fontSize = 4.3.sp, fontFamily = FontFamily.Monospace, color = palette.textSecondary, maxLines = 1)
                                        if (isLiveStats) {
                                            Text(dur, fontSize = 6.8.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, maxLines = 1)
                                            Text(sub, fontSize = 4.2.sp, fontFamily = FontFamily.Monospace, color = palette.textSecondary, maxLines = 1)
                                        } else {
                                            ShadowBar(0.65f, 4.5.dp, palette.textPrimary.copy(alpha = 0.7f))
                                            ShadowBar(0.5f, 3.dp, palette.textSecondary.copy(alpha = 0.4f))
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(thickness = 0.45.dp, color = palette.cardBorder)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    if (isLiveStats) "30D $monthDurStr (${stats.monthPlays})" else "30D --",
                                    fontSize = 4.6.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.textPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    if (isLiveStats) "TOTAL $allTimeDurStr (${stats.allTimePlays})" else "TOTAL --",
                                    fontSize = 4.6.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.textPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // CH // PLAYBACK LOG + Recently Played: 3 numbered square artwork cards (01, 02, 03)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.5.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("CH // PLAYBACK LOG • Recently Played", fontSize = 5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = palette.accent, maxLines = 1)
                        Text("See all →", fontSize = 4.6.sp, color = palette.textSecondary)
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { idx ->
                            val item = if (liveCtx.hasLiveRecents) liveCtx.recentSongs.getOrNull(idx) else null
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).border(0.5.dp, palette.cardBorder, RoundedCornerShape(6.dp))) {
                                MockArtworkSurface(item, item == null, palette, 6, Modifier.fillMaxSize(), 10.dp)
                                Surface(
                                    shape = RoundedCornerShape(3.dp),
                                    color = Color.Black.copy(alpha = 0.72f),
                                    modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
                                ) {
                                    Text("0${idx + 1}", fontSize = 4.2.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 2.5.dp, vertical = 0.5.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Floating Mini Player + Floating Pill Bottom Nav (Home, Library, Playlists, Search, Jam)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = palette.cardBg,
            border = BorderStroke(0.6.dp, palette.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape)) {
                            MockArtworkSurface(song, !useLive, palette, 999, Modifier.fillMaxSize(), 8.dp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            if (useLive && song != null) {
                                Text(song.displayTitle, fontSize = 6.8.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.displayArtist, fontSize = 5.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                ShadowBar(0.55f, 4.dp, palette.textPrimary.copy(alpha = 0.68f))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isFav) BentoAccentPinkText else palette.textSecondary, modifier = Modifier.size(8.5.dp))
                        Box(modifier = Modifier.size(15.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                            Icon(playIcon, null, tint = palette.onAccent, modifier = Modifier.size(8.5.dp))
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.5.dp).background(palette.textPrimary.copy(alpha = 0.12f))) {
                    Box(modifier = Modifier.fillMaxWidth(posFraction).fillMaxHeight().background(palette.accent))
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(999.dp),
            color = palette.cardBg,
            border = BorderStroke(0.5.dp, palette.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(999.dp), color = palette.accent) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Default.Home, null, tint = palette.onAccent, modifier = Modifier.size(8.dp))
                        Text("Home", fontSize = 5.8.sp, fontWeight = FontWeight.Bold, color = palette.onAccent)
                    }
                }
                Icon(Icons.Default.LibraryMusic, null, tint = palette.textSecondary, modifier = Modifier.size(9.dp))
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = palette.textSecondary, modifier = Modifier.size(9.dp))
                Icon(Icons.Default.Search, null, tint = palette.textSecondary, modifier = Modifier.size(9.dp))
                Icon(Icons.Default.Groups, null, tint = palette.textSecondary, modifier = Modifier.size(9.dp))
            }
        }
    }
}

private fun formatSectionLabel(rawId: String): String {
    val cleanId = rawId.removePrefix("HIDDEN:").uppercase(Locale.US)
    return when {
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
}

@Composable
private fun LivePlaylistsMockup(
    layout: PlaylistsLayout,
    palette: StudioMockPalette,
    liveCtx: StudioLiveContext
) {
    val livePlaylists = remember(liveCtx.playlists, liveCtx.isShadowMode) {
        if (!liveCtx.isShadowMode) liveCtx.playlists.take(6) else emptyList()
    }
    val names = remember(livePlaylists, liveCtx.playlistNames, liveCtx.hasLivePlaylists) {
        when {
            livePlaylists.isNotEmpty() -> livePlaylists.map { it.name }
            liveCtx.hasLivePlaylists -> liveCtx.playlistNames.take(6)
            else -> emptyList()
        }
    }
    val useLive = names.isNotEmpty()
    val countLabel = if (useLive) {
        (if (liveCtx.playlists.isNotEmpty()) liveCtx.playlists.size else liveCtx.playlistNames.size).toString()
    } else {
        "4"
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        when (layout) {
            PlaylistsLayout.BENTO_GRID -> {
                // Exact Bento Grid header: rounded-square ArrowBack + "BENTO COLLECTIONS" / "Playlists" + 3 circles (Search, Favorite, ViewList)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(modifier = Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(palette.cardBg).border(0.5.dp, palette.cardBorder, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = palette.textPrimary, modifier = Modifier.size(9.dp))
                        }
                        Column {
                            Text("BENTO COLLECTIONS", fontSize = 5.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = palette.accent)
                            Text("Playlists", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf(Icons.Default.Search, Icons.Default.Favorite, Icons.Default.ViewList).forEach { ic ->
                            Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.cardBg).border(0.5.dp, palette.cardBorder, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(ic, null, tint = palette.textPrimary, modifier = Modifier.size(8.5.dp))
                            }
                        }
                    }
                }

                // Asymmetric action dock: New Mix accent pill + Smart pill + FileDownload orb
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(999.dp), color = palette.accent, modifier = Modifier.weight(1.2f).height(20.dp)) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, null, tint = palette.onAccent, modifier = Modifier.size(9.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("New Mix", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.onAccent)
                        }
                    }
                    Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.6.dp, palette.cardBorder), modifier = Modifier.weight(1f).height(20.dp)) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, tint = palette.accent, modifier = Modifier.size(9.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Smart", fontSize = 7.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                        }
                    }
                    Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(palette.cardBg).border(0.6.dp, palette.cardBorder, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.FileDownload, null, tint = palette.textPrimary, modifier = Modifier.size(10.dp))
                    }
                }

                // 2-column Bento playlist cards — real custom cover or real 4-grid fallback cover (never random recentSongs)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    repeat(2) { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                            repeat(2) { c ->
                                val idx = r * 2 + c
                                val pl = livePlaylists.getOrNull(idx)
                                val plName = pl?.name ?: names.getOrNull(idx)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = palette.cardBg,
                                    border = BorderStroke(0.6.dp, palette.cardBorder),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                ) {
                                    Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))) {
                                            MockPlaylistCoverSurface(
                                                playlist = pl,
                                                isShadow = liveCtx.isShadowMode || pl == null,
                                                palette = palette,
                                                cornerRadius = 8,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        if (useLive && plName != null) {
                                            Text(plName, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                pl?.let { "${it.songCount} track${if (it.songCount == 1) "" else "s"}" } ?: "Playlist",
                                                fontSize = 6.5.sp,
                                                color = palette.textSecondary
                                            )
                                        } else {
                                            ShadowBar(0.78f, 5.dp, palette.textPrimary.copy(alpha = 0.68f))
                                            Spacer(Modifier.height(2.5.dp))
                                            ShadowBar(0.45f, 3.5.dp, palette.textSecondary.copy(alpha = 0.42f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            PlaylistsLayout.MAGAZINE_HERO -> {
                // Exact Magazine Hero masthead: "EDITORIAL ISSUE • CURATED ANTHOLOGY" + serif "The Record" + 4 circles (Search, FileUpload, AutoAwesome, accent Add) + divider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("EDITORIAL ISSUE • CURATED ANTHOLOGY", fontSize = 5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = palette.accent)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("The Record", fontSize = 13.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf(Icons.Default.Search, Icons.Default.FileUpload, Icons.Default.AutoAwesome).forEach { ic ->
                                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.cardBg).border(0.5.dp, palette.cardBorder, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(ic, null, tint = palette.textPrimary, modifier = Modifier.size(8.5.dp))
                                }
                            }
                            Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, null, tint = palette.onAccent, modifier = Modifier.size(9.dp))
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.6.dp, color = palette.cardBorder)
                }

                // Tall featured hero cover card — uses first real playlist's custom cover or 4-grid fallback
                val heroPlaylist = livePlaylists.firstOrNull()
                Box(modifier = Modifier.fillMaxWidth().height(98.dp).clip(RoundedCornerShape(14.dp))) {
                    MockPlaylistCoverSurface(
                        playlist = heroPlaylist,
                        isShadow = liveCtx.isShadowMode || heroPlaylist == null,
                        palette = palette,
                        cornerRadius = 14,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))))
                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("FEATURED ANTHOLOGY", fontSize = 6.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.82f))
                        if (useLive && names.isNotEmpty()) {
                            Text(names.first(), fontSize = 11.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            ShadowBar(0.65f, 7.dp, Color.White.copy(alpha = 0.82f))
                        }
                    }
                }

                // Editorial numbered rows
                Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                    repeat(3) { idx ->
                        val rowName = names.getOrNull(idx + 1)
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("0${idx + 1}", fontSize = 8.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = palette.accent)
                            if (useLive && rowName != null) {
                                Text(rowName, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            } else {
                                Box(modifier = Modifier.weight(1f)) { ShadowBar(0.68f, 5.dp, palette.textPrimary.copy(alpha = 0.68f)) }
                            }
                            Icon(Icons.Default.PlayArrow, null, tint = palette.accent, modifier = Modifier.size(12.dp))
                        }
                        if (idx < 2) HorizontalDivider(thickness = 0.4.dp, color = palette.cardBorder.copy(alpha = 0.7f))
                    }
                }
            }

            PlaylistsLayout.COMPACT_LIST -> {
                // Exact Compact List header: monospace "AUDIOPHILE DIRECTORY // COMPACT STREAM" + "Index (N)" + 3 circles (FileUpload, AutoAwesome, accent Add)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("AUDIOPHILE DIRECTORY // COMPACT STREAM", fontSize = 5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = palette.accent)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Index ($countLabel)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf(Icons.Default.FileUpload, Icons.Default.AutoAwesome).forEach { ic ->
                                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.cardBg).border(0.5.dp, palette.cardBorder, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(ic, null, tint = palette.textPrimary, modifier = Modifier.size(8.5.dp))
                                }
                            }
                            Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, null, tint = palette.onAccent, modifier = Modifier.size(9.dp))
                            }
                        }
                    }
                }

                // Pill "Quick filter N collections..." search bar
                Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Default.Search, null, tint = palette.textSecondary, modifier = Modifier.size(9.dp))
                        Text("Quick filter $countLabel collections...", fontSize = 6.5.sp, color = palette.textSecondary)
                    }
                }

                // Dense numbered rows with real playlist covers (custom cover or 4-grid fallback)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    repeat(5) { idx ->
                        val pl = livePlaylists.getOrNull(idx)
                        val plName = pl?.name ?: names.getOrNull(idx)
                        Surface(shape = RoundedCornerShape(8.dp), color = palette.cardBg, border = BorderStroke(0.4.dp, palette.cardBorder), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("0${idx + 1}", fontSize = 6.5.sp, fontFamily = FontFamily.Monospace, color = palette.accent)
                                Box(modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))) {
                                    MockPlaylistCoverSurface(
                                        playlist = pl,
                                        isShadow = liveCtx.isShadowMode || pl == null,
                                        palette = palette,
                                        cornerRadius = 4,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                if (useLive && plName != null) {
                                    Text(plName, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                } else {
                                    Box(modifier = Modifier.weight(1f)) { ShadowBar(0.65f, 4.5.dp, palette.textPrimary.copy(alpha = 0.65f)) }
                                }
                                Icon(Icons.Default.MoreVert, null, tint = palette.textSecondary, modifier = Modifier.size(10.dp))
                            }
                        }
                    }
                }
            }

            PlaylistsLayout.GLOW_CARDS -> {
                // Exact Neon Glow Cards header: "NEON GLOW CARDS" + "Playlists (N)" + 3 circles (FileUpload, AutoAwesome, accent Add)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("NEON GLOW CARDS", fontSize = 5.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = palette.accent)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Playlists ($countLabel)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf(Icons.Default.FileUpload, Icons.Default.AutoAwesome).forEach { ic ->
                                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.cardBg).border(0.5.dp, palette.accent.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(ic, null, tint = palette.textPrimary, modifier = Modifier.size(8.5.dp))
                                }
                            }
                            Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, null, tint = palette.onAccent, modifier = Modifier.size(9.dp))
                            }
                        }
                    }
                }

                // Exact 2-column gradient-bordered square-art cards with real PlaylistCover (custom cover or 4-grid fallback)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    repeat(2) { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                            repeat(2) { c ->
                                val idx = r * 2 + c
                                val pl = livePlaylists.getOrNull(idx)
                                val plName = pl?.name ?: names.getOrNull(idx)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = palette.accent.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, Brush.linearGradient(listOf(palette.accent, palette.accent.copy(alpha = 0.25f)))),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                ) {
                                    Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                            Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
                                                MockPlaylistCoverSurface(
                                                    playlist = pl,
                                                    isShadow = liveCtx.isShadowMode || pl == null,
                                                    palette = palette,
                                                    cornerRadius = 8,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(4.dp)
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .background(palette.accent),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.PlayArrow, null, tint = palette.onAccent, modifier = Modifier.size(10.dp))
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                                                if (useLive && plName != null) {
                                                    Text(plName, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        pl?.let { "${it.songCount} track${if (it.songCount == 1) "" else "s"}" } ?: "Playlist",
                                                        fontSize = 6.sp,
                                                        color = palette.accent
                                                    )
                                                } else {
                                                    ShadowBar(0.75f, 4.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                                                    ShadowBar(0.45f, 3.5.dp, palette.accent.copy(alpha = 0.58f))
                                                }
                                            }
                                            Icon(Icons.Default.MoreVert, null, tint = palette.textSecondary, modifier = Modifier.size(9.dp))
                                        }
                                    }
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
    val playIcon = if (useLiveSong && liveCtx.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow

    when (layout) {
        LyricsLayout.CLASSIC_DEFAULT -> {
            // Exact Classic Bento LyricsPanelContent structure:
            // Header: "Lyrics" + "SYNCED" pill + song title | Search circle, Offset, .LRC File, KeyboardArrowDown circle
            // Centered synced lyrics stack
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Lyrics", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                                Surface(shape = RoundedCornerShape(999.dp), color = palette.accent.copy(alpha = 0.22f)) {
                                    Text("SYNCED", fontSize = 5.5.sp, fontWeight = FontWeight.Bold, color = palette.accent, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp))
                                }
                            }
                            if (useLiveSong && song != null) {
                                Text(song.displayTitle, fontSize = 7.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                ShadowBar(0.55f, 4.dp, palette.textSecondary.copy(alpha = 0.45f))
                            }
                        }
                        Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.cardBg), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                        }
                    }

                    // Action pills row matching real LyricsPanelContent (Search, Offset, .LRC File)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            Icons.Default.Search to "Search",
                            Icons.Default.Tune to "Offset",
                            Icons.Default.Description to ".LRC File"
                        ).forEach { (ic, lbl) ->
                            Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.weight(1f)) {
                                Row(modifier = Modifier.padding(vertical = 3.5.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(ic, null, tint = palette.textSecondary, modifier = Modifier.size(8.dp))
                                    Spacer(Modifier.width(2.5.dp))
                                    Text(lbl, fontSize = 6.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (hasLyrics) {
                            val line0 = liveLines.getOrNull(0)?.second.orEmpty()
                            val line1 = liveLines.getOrNull(1)?.second ?: line0
                            val line2 = liveLines.getOrNull(2)?.second.orEmpty()
                            val line3 = liveLines.getOrNull(3)?.second.orEmpty()
                            if (line0.isNotBlank()) Text(line0, fontSize = 8.5.sp, color = palette.textSecondary.copy(alpha = 0.45f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(line1, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (line2.isNotBlank()) Text(line2, fontSize = 8.5.sp, color = palette.textSecondary.copy(alpha = 0.55f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (line3.isNotBlank()) Text(line3, fontSize = 8.sp, color = palette.textSecondary.copy(alpha = 0.35f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            ShadowBar(0.68f, 6.dp, palette.textSecondary.copy(alpha = 0.35f))
                            ShadowBar(0.86f, 10.dp, palette.textPrimary.copy(alpha = 0.85f))
                            ShadowBar(0.72f, 6.dp, palette.textSecondary.copy(alpha = 0.42f))
                            ShadowBar(0.56f, 5.dp, palette.textSecondary.copy(alpha = 0.28f))
                        }
                    }
                }
            }
        }

        LyricsLayout.FLUID_GLOW -> {
            // Exact Fluid Glow structure:
            // Top row: rounded-square art + Title/Artist + circle Play/Pause + circle KeyboardArrowDown
            // Second row toolbar strip: Search, Offset, Translate, .LRC
            // Large bold left-aligned lyrics
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))) {
                        MockArtworkSurface(song, !useLiveSong, palette, 6, Modifier.fillMaxSize(), 11.dp)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                        if (useLiveSong && song != null) {
                            Text(song.displayTitle, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.displayArtist, fontSize = 6.5.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            ShadowBar(0.68f, 5.5.dp, palette.textPrimary.copy(alpha = 0.72f))
                            ShadowBar(0.45f, 4.dp, palette.textSecondary.copy(alpha = 0.45f))
                        }
                    }
                    Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                        Icon(playIcon, null, tint = palette.onAccent, modifier = Modifier.size(10.dp))
                    }
                    Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.cardBg), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                    }
                }

                // Toolbar strip: Search, Offset, Translate, .LRC
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                    listOf(
                        Icons.Default.Search to "Search",
                        Icons.Default.Tune to "Offset",
                        Icons.Default.Translate to "Translate",
                        Icons.Default.Description to ".LRC"
                    ).forEach { (ic, lbl) ->
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.weight(1f)) {
                            Row(modifier = Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(ic, null, tint = palette.textSecondary, modifier = Modifier.size(7.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(lbl, fontSize = 5.2.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1)
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (hasLyrics) {
                            val line0 = liveLines.getOrNull(0)?.second.orEmpty()
                            val line1 = liveLines.getOrNull(1)?.second ?: line0
                            val line2 = liveLines.getOrNull(2)?.second.orEmpty()
                            val line3 = liveLines.getOrNull(3)?.second.orEmpty()
                            if (line0.isNotBlank()) Text(line0, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary.copy(alpha = 0.42f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(line1, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (line2.isNotBlank()) Text(line2, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textSecondary.copy(alpha = 0.58f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (line3.isNotBlank()) Text(line3, fontSize = 8.5.sp, color = palette.textSecondary.copy(alpha = 0.35f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            ShadowBar(0.62f, 6.dp, palette.textSecondary.copy(alpha = 0.35f))
                            ShadowBar(0.92f, 11.dp, palette.textPrimary.copy(alpha = 0.88f))
                            ShadowBar(0.74f, 11.dp, palette.textPrimary.copy(alpha = 0.85f))
                            ShadowBar(0.68f, 6.dp, palette.textSecondary.copy(alpha = 0.45f))
                            ShadowBar(0.50f, 5.dp, palette.textSecondary.copy(alpha = 0.28f))
                        }
                    }
                }
            }
        }

        LyricsLayout.EDITORIAL_TIMELINE -> {
            // Exact Studio Timeline structure:
            // Top row: dot "TIMELINE STUDIO" + "Title — Artist" + circle KeyboardArrowDown
            // Second row toolbar strip: Search, Offset, Translate, .LRC
            // Left monospace timestamp rail + vertical timeline track + glowing nodes + lyrics
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(palette.accent))
                            Text("TIMELINE STUDIO", fontSize = 6.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp, color = palette.accent)
                        }
                        if (useLiveSong && song != null) {
                            Text("${song.displayTitle} — ${song.displayArtist}", fontSize = 7.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            ShadowBar(0.65f, 4.dp, palette.textSecondary.copy(alpha = 0.45f))
                        }
                    }
                    Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.cardBg), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                    }
                }

                // Toolbar strip
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                    listOf(
                        Icons.Default.Search to "Search",
                        Icons.Default.Tune to "Offset",
                        Icons.Default.Translate to "Translate",
                        Icons.Default.Description to ".LRC"
                    ).forEach { (ic, lbl) ->
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.cardBg, border = BorderStroke(0.5.dp, palette.cardBorder), modifier = Modifier.weight(1f)) {
                            Row(modifier = Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(ic, null, tint = palette.textSecondary, modifier = Modifier.size(7.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(lbl, fontSize = 5.2.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1)
                            }
                        }
                    }
                }

                // Timeline rail + glowing nodes + lyrics
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Continuous vertical timeline rail aligned with nodes
                    Box(
                        modifier = Modifier
                            .padding(start = 34.dp, top = 8.dp, bottom = 8.dp)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(palette.textSecondary.copy(alpha = 0.25f))
                    )
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val rowCount = if (hasLyrics) liveLines.take(4).size else 4
                        repeat(rowCount) { idx ->
                            val active = idx == 1 || (rowCount == 1 && idx == 0)
                            val ts = if (hasLyrics) liveLines[idx].first else listOf("01:12", "01:18", "01:25", "01:32")[idx]
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(
                                    text = ts,
                                    fontSize = 7.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) palette.accent else palette.textSecondary.copy(alpha = 0.45f),
                                    modifier = Modifier.width(25.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(if (active) 7.dp else 4.5.dp)
                                        .clip(CircleShape)
                                        .background(if (active) palette.accent else palette.textSecondary.copy(alpha = 0.4f))
                                )
                                if (hasLyrics) {
                                    Text(
                                        text = liveLines[idx].second,
                                        fontSize = if (active) 10.5.sp else 8.sp,
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
                                            height = if (active) 8.dp else 5.dp,
                                            color = if (active) palette.textPrimary.copy(alpha = 0.82f) else palette.textSecondary.copy(alpha = 0.38f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
