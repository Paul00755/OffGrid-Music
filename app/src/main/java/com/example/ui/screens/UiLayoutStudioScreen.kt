package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp as floatLerp
import com.example.ui.components.WaveformMiniPreviewCanvas
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.launch
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

/**
 * Dedicated full-screen Swipeable Live UI Layout Preview Studio (inspired by Screenshot 8).
 * Lets users swipe full-height realistic phone mockups across Now Playing, Home Screen,
 * Playlists, Lyrics UI, and a unified Combo View — dynamically tinted with their live
 * themeMode, accent color, dark-colors preference, gradient/glass toggles, waveform style,
 * and active Home Feed sections — before confirming with "Set".
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
                    contentDescription = "Back to UI & Theme",
                    tint = BentoTextPrimary
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Screen Layout Studio",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Swipe live previews with your active theme & feed",
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
            LayoutStudioTab.COMBO -> 4 // Live 4-screen suite: Home, Now Playing, Lyrics, Playlists
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

            // Sync staged selection automatically when user swipes through a single-screen tab
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
                LayoutStudioTab.COMBO -> "Live combination preview using your selected theme, gradient & feed"
            }

            // Centered Layout Title Header (matching Screenshot 8's bold centered layout name above the phone mockup)
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

            // Swipeable Phone Mockup Carousel with neighboring screens peeking on left & right
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
                                showAdvancedSongInfo = showAdvancedSongInfo
                            )
                            LayoutStudioTab.HOME -> LiveHomeMockup(
                                layout = HomeScreenLayout.entries[page],
                                palette = palette,
                                waveformStyle = waveformStyle,
                                feedSections = effectiveFeedSections
                            )
                            LayoutStudioTab.PLAYLISTS -> LivePlaylistsMockup(
                                layout = PlaylistsLayout.entries[page],
                                palette = palette
                            )
                            LayoutStudioTab.LYRICS -> LiveLyricsMockup(
                                layout = LyricsLayout.entries[page],
                                palette = palette
                            )
                            LayoutStudioTab.COMBO -> when (page) {
                                0 -> LiveHomeMockup(
                                    layout = HomeScreenLayout.fromId(stagedHome),
                                    palette = palette,
                                    waveformStyle = waveformStyle,
                                    feedSections = effectiveFeedSections
                                )
                                1 -> LiveNowPlayingMockup(
                                    layout = NowPlayingLayout.fromId(stagedNowPlaying),
                                    palette = palette,
                                    waveformStyle = waveformStyle,
                                    showVolumeBar = showVolumeBar,
                                    showAdvancedSongInfo = showAdvancedSongInfo
                                )
                                2 -> LiveLyricsMockup(
                                    layout = LyricsLayout.fromId(stagedLyrics),
                                    palette = palette
                                )
                                else -> LivePlaylistsMockup(
                                    layout = PlaylistsLayout.fromId(stagedPlaylists),
                                    palette = palette
                                )
                            }
                        }
                    }
                }
            }

            // Quick Option Chips Strip (lets user tap any layout directly OR swipe)
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
                // Page indicator dots in Combo View
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

        // Bottom Confirmation Bar ("Preview Combo" quick button + Primary "Set" pill button)
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
    val baseBg = when {
        isLight -> Color(0xFFF6F7FB)
        isAmoled -> Color(0xFF000000)
        else -> Color(0xFF0E1018)
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
        isAmoled -> Color(0xFF101218)
        else -> Color(0xFF181C28)
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
private fun LiveNowPlayingMockup(
    layout: NowPlayingLayout,
    palette: StudioMockPalette,
    waveformStyle: String,
    showVolumeBar: Boolean,
    showAdvancedSongInfo: Boolean
) {
    when (layout) {
        NowPlayingLayout.MODERN_BENTO -> {
            // Full-bleed top artwork seamlessly dissolving into bottom stage
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.56f)
                        .background(Brush.verticalGradient(listOf(palette.artworkTop, palette.artworkBottom)))
                ) {
                    Box(
                        modifier = Modifier
                            .padding(10.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                    Icon(
                        Icons.Default.MusicNote,
                        null,
                        tint = Color.White.copy(alpha = 0.32f),
                        modifier = Modifier.size(46.dp).align(Alignment.Center)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.45f)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, palette.bgBottom)))
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Idhayaa", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1)
                            Text("Thaman S, Vishal", fontSize = 9.5.sp, color = palette.textSecondary, maxLines = 1)
                            if (showAdvancedSongInfo) {
                                Text("FLAC • 44.1 kHz • 940 kbps", fontSize = 7.5.sp, fontFamily = FontFamily.Monospace, color = palette.textSecondary.copy(alpha = 0.78f), maxLines = 1)
                            }
                        }
                        Box(
                            modifier = Modifier.size(22.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.MoreVert, null, tint = palette.textPrimary, modifier = Modifier.size(12.dp)) }
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier.size(22.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.FavoriteBorder, null, tint = palette.textPrimary, modifier = Modifier.size(12.dp)) }
                    }

                    WaveformMiniPreviewCanvas(
                        styleId = waveformStyle,
                        accentColor = palette.textPrimary,
                        trackColor = palette.textPrimary.copy(alpha = 0.24f),
                        modifier = Modifier.fillMaxWidth().height(18.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.SkipPrevious, null, tint = palette.textPrimary, modifier = Modifier.size(20.dp))
                        Icon(Icons.Default.PlayArrow, null, tint = palette.textPrimary, modifier = Modifier.size(26.dp))
                        Icon(Icons.Default.SkipNext, null, tint = palette.textPrimary, modifier = Modifier.size(20.dp))
                    }

                    if (showVolumeBar) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.VolumeDown, null, tint = palette.textSecondary, modifier = Modifier.size(10.dp))
                            Box(modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.22f))) {
                                Box(modifier = Modifier.fillMaxWidth(0.68f).fillMaxHeight().background(palette.accent))
                            }
                            Icon(Icons.Default.VolumeUp, null, tint = palette.textSecondary, modifier = Modifier.size(10.dp))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                        Icon(Icons.Default.Shuffle, null, tint = palette.textPrimary, modifier = Modifier.size(13.dp))
                        Surface(shape = RoundedCornerShape(999.dp), color = palette.textPrimary.copy(alpha = 0.12f)) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Speaker, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                                Icon(Icons.Default.Timer, null, tint = palette.textPrimary, modifier = Modifier.size(11.dp))
                            }
                        }
                        Icon(Icons.Default.Repeat, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                        Icon(Icons.Default.Lyrics, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }

        NowPlayingLayout.VINYL_TURNTABLE -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(15.dp))
                    Text("NOW PLAYING", fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = palette.textSecondary)
                    Icon(Icons.Default.MoreVert, null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
                }

                // Vinyl Disk
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFF282B36), Color(0xFF0A0B10))))
                        .border(2.dp, palette.accent.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(palette.artworkTop, palette.accent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF0A0B10)))
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Midnight Groove", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    Text("Studio Session", fontSize = 9.5.sp, color = palette.textSecondary)
                    if (showAdvancedSongInfo) {
                        Text("MP3 • 48 kHz • 320 kbps", fontSize = 7.5.sp, color = palette.accent)
                    }
                }

                WaveformMiniPreviewCanvas(
                    styleId = waveformStyle,
                    accentColor = palette.accent,
                    trackColor = palette.textPrimary.copy(alpha = 0.22f),
                    modifier = Modifier.fillMaxWidth().height(18.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shuffle, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                    Icon(Icons.Default.SkipPrevious, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                    Box(
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(palette.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Pause, null, tint = palette.onAccent, modifier = Modifier.size(18.dp))
                    }
                    Icon(Icons.Default.SkipNext, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                    Icon(Icons.Default.Repeat, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                }
            }
        }

        NowPlayingLayout.MINIMAL_GLASS -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(palette.artworkTop, palette.artworkBottom)))
                    .padding(12.dp)
            ) {
                Icon(
                    Icons.Default.Album,
                    null,
                    tint = Color.White.copy(alpha = 0.22f),
                    modifier = Modifier.size(72.dp).align(Alignment.TopCenter).padding(top = 28.dp)
                )

                // Frosted Glass Bottom Card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.42f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Starlight Echo", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Ambient Collective", fontSize = 9.sp, color = Color.White.copy(alpha = 0.72f))
                            }
                            Icon(Icons.Default.Favorite, null, tint = palette.accent, modifier = Modifier.size(14.dp))
                        }
                        WaveformMiniPreviewCanvas(
                            styleId = waveformStyle,
                            accentColor = Color.White,
                            trackColor = Color.White.copy(alpha = 0.28f),
                            modifier = Modifier.fillMaxWidth().height(16.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Box(
                                modifier = Modifier.size(30.dp).clip(CircleShape).background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            }
                            Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        NowPlayingLayout.NEON_WAVEFORM -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(15.dp))
                    Box(modifier = Modifier.width(24.dp).height(3.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.35f)))
                    Icon(Icons.Default.MoreHoriz, null, tint = palette.textPrimary, modifier = Modifier.size(15.dp))
                }

                // Rounded Floating Cover Card (matches Screenshot 1 / Neon Studio)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(palette.artworkTop, palette.accent.copy(alpha = 0.78f), palette.artworkBottom))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(44.dp))
                }

                WaveformMiniPreviewCanvas(
                    styleId = waveformStyle,
                    accentColor = Color.White,
                    trackColor = palette.textPrimary.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth().height(18.dp)
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sudasuda Thooral", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    Text("Yuvan Shankar Raja", fontSize = 9.sp, color = palette.textSecondary)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shuffle, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                    Icon(Icons.Default.SkipPrevious, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                    Icon(Icons.Default.Pause, null, tint = palette.textPrimary, modifier = Modifier.size(22.dp))
                    Icon(Icons.Default.SkipNext, null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                    Icon(Icons.Default.FavoriteBorder, null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
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
    feedSections: List<String>
) {
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
                Text("Welcome, Music Lover", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1)
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
                            Box(
                                modifier = Modifier.size(34.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(palette.artworkTop, palette.accent)))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Continue Listening", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary, maxLines = 1)
                                Text("Idhayaa • Thaman S", fontSize = 7.5.sp, color = palette.accent, maxLines = 1)
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
                        color = palette.accent.copy(alpha = 0.22f),
                        border = BorderStroke(0.8.dp, palette.accent.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1.25f).height(64.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text("BENTO MIX", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = palette.accent)
                            Text("Instant Play", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
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
                            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(palette.accent))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Quick Stream", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                                Text("Clean dense list", fontSize = 7.sp, color = palette.textSecondary)
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
                            Text("42m today", fontSize = 7.5.sp, color = palette.textSecondary)
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

        // Dynamic User Home Feed Sections preview (reflects actual enabled sections from Settings!)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            feedSections.take(3).forEach { rawId ->
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
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = palette.cardBg,
                                border = BorderStroke(0.5.dp, palette.cardBorder),
                                modifier = Modifier.weight(1f).height(34.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                                            .background(if (idx == 0) palette.accent.copy(alpha = 0.35f) else palette.artworkTop.copy(alpha = 0.45f))
                                    )
                                    Column {
                                        Box(modifier = Modifier.width(34.dp).height(4.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = 0.7f)))
                                        Spacer(Modifier.height(3.dp))
                                        Box(modifier = Modifier.width(22.dp).height(3.dp).clip(CircleShape).background(palette.textSecondary.copy(alpha = 0.45f)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Floating Mini Player + Nav Pill
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(palette.artworkTop))
                    Text("Idhayaa", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                }
                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, tint = palette.onAccent, modifier = Modifier.size(10.dp))
                }
            }
        }
    }
}

@Composable
private fun LivePlaylistsMockup(
    layout: PlaylistsLayout,
    palette: StudioMockPalette
) {
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
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = palette.cardBg,
                                    border = BorderStroke(0.7.dp, palette.cardBorder),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                ) {
                                    Column(modifier = Modifier.padding(7.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                                                .background(if ((r + c) % 2 == 0) palette.artworkTop else palette.accent.copy(alpha = 0.55f))
                                        )
                                        Spacer(Modifier.height(5.dp))
                                        Text(listOf("Late Drive", "Chill Mix", "Favorites", "Workout")[r * 2 + c], fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                                        Text("${18 + r * 9 + c * 4} tracks", fontSize = 7.sp, color = palette.textSecondary)
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
                        .background(Brush.linearGradient(listOf(palette.artworkTop, palette.accent)))
                        .padding(10.dp)
                ) {
                    Column(modifier = Modifier.align(Alignment.BottomStart)) {
                        Text("FEATURED", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
                        Text("Evening Acoustic", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    listOf("Tamil Classics • 34", "Focus Flow • 22", "Roadtrip • 41").forEach { item ->
                        Surface(shape = RoundedCornerShape(10.dp), color = palette.cardBg, border = BorderStroke(0.6.dp, palette.cardBorder), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                                Icon(Icons.Default.PlayArrow, null, tint = palette.accent, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }

            PlaylistsLayout.COMPACT_LIST -> {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                    listOf("Favorites", "Late Night", "Workout", "Acoustic", "Roadtrip", "Synthwave").forEachIndexed { idx, name ->
                        Surface(shape = RoundedCornerShape(9.dp), color = palette.cardBg, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(if (idx % 2 == 0) palette.accent.copy(alpha = 0.45f) else palette.artworkTop))
                                Text(name, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.PlayArrow, null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }

            PlaylistsLayout.GLOW_CARDS -> {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                    listOf("Neon Pulse", "Velvet Jazz", "Rainy Lo-Fi").forEach { title ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.accent.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                                    Text("24 songs • Curated", fontSize = 7.5.sp, color = palette.accent)
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
    palette: StudioMockPalette
) {
    when (layout) {
        LyricsLayout.CLASSIC_DEFAULT -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
                    Text("SYNCED LYRICS", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = palette.accent)
                    Icon(Icons.Default.Translate, null, tint = palette.textSecondary, modifier = Modifier.size(13.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Softly drifting through the night", fontSize = 9.5.sp, color = palette.textSecondary.copy(alpha = 0.45f), textAlign = TextAlign.Center)
                    Text("Golden skies begin to glow", fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, textAlign = TextAlign.Center)
                    Text("Every chord becomes a memory", fontSize = 9.5.sp, color = palette.textSecondary.copy(alpha = 0.55f), textAlign = TextAlign.Center)
                    Text("Echoing across the avenue", fontSize = 9.sp, color = palette.textSecondary.copy(alpha = 0.35f), textAlign = TextAlign.Center)
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
                    Box(modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Brush.linearGradient(listOf(palette.artworkTop, palette.accent))))
                    Column {
                        Text("Idhayaa", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        Text("Thaman S", fontSize = 8.sp, color = palette.textSecondary)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Fading whispers in the rain", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary.copy(alpha = 0.42f))
                    Text("Hold the melody close to your heart", fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, color = palette.textPrimary, lineHeight = 19.sp)
                    Text("As the city lights turn blue", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textSecondary.copy(alpha = 0.58f))
                    Text("Dancing slow under starlit skies", fontSize = 9.5.sp, color = palette.textSecondary.copy(alpha = 0.35f))
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
                    listOf(
                        Triple("01:12", "Softly drifting through the night", false),
                        Triple("01:18", "Golden skies begin to glow", true),
                        Triple("01:25", "Every chord becomes a memory", false),
                        Triple("01:32", "Echoing across the avenue", false)
                    ).forEach { (ts, line, active) ->
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
                            Text(
                                text = line,
                                fontSize = if (active) 11.5.sp else 9.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) palette.textPrimary else palette.textSecondary.copy(alpha = 0.5f),
                                modifier = Modifier.weight(1f)
                            )
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
