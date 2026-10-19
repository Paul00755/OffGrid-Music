package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import com.example.data.model.FolderInfo
import com.example.service.SleepTimerState
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.TranscribeLanguage
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.HapticUtils

enum class SettingsSection(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ALL("All", Icons.Default.Tune),
    AUDIO("Audio & Sound", Icons.Default.Equalizer),
    UI("UI & Theme", Icons.Default.Palette),
    HOME_FEED("Home Feed", Icons.Default.DashboardCustomize),
    CACHE("Cache & Storage", Icons.Default.Storage),
    PLAYBACK("Playback & Timers", Icons.Default.PlayCircle),
    LIBRARY("Library & Folders", Icons.Default.Folder),
    OTHER("Other & Privacy", Icons.Default.Security),
    CLOUD_SYNC("Cloud & Sync", Icons.Default.CloudSync)
}

data class SettingsCategoryCardItem(
    val section: SettingsSection,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color
)

val SETTINGS_CATEGORY_CARD_ITEMS = listOf(
    SettingsCategoryCardItem(SettingsSection.AUDIO, "Audio & Sound", "Crossfade, Equalizer, Loudness normalization & Balance", Icons.Default.Equalizer, Color(0xFF00E5FF)),
    SettingsCategoryCardItem(SettingsSection.UI, "UI & Theme", "Dark/Light/AMOLED, Dynamic colors & Screen layouts", Icons.Default.Palette, Color(0xFFBB86FC)),
    SettingsCategoryCardItem(SettingsSection.HOME_FEED, "Home Feed", "Customize home sections, Reorder & Preset feeds", Icons.Default.DashboardCustomize, Color(0xFF7C4DFF)),
    SettingsCategoryCardItem(SettingsSection.PLAYBACK, "Playback & Timers", "Sleep timer, Gestures, Shake to skip & Queue behavior", Icons.Default.PlayCircle, Color(0xFFFF9100)),
    SettingsCategoryCardItem(SettingsSection.LIBRARY, "Library & Folders", "Scan device storage, Exclude folders & Tag cleaning", Icons.Default.Folder, Color(0xFF2979FF)),
    SettingsCategoryCardItem(SettingsSection.CACHE, "Cache & Storage", "Artwork cache, Lyrics storage & SQLite database optimization", Icons.Default.Storage, Color(0xFF00E676)),
    SettingsCategoryCardItem(SettingsSection.CLOUD_SYNC, "Cloud & Sync", "Google Account, Cloud backup & Cross-device sync", Icons.Default.CloudSync, Color(0xFF00E5FF)),
    SettingsCategoryCardItem(SettingsSection.OTHER, "Other & Privacy", "Search history, Diagnostics logs, Crash reports & App info", Icons.Default.Security, Color(0xFFFF5252))
)

private fun matchesSettingsSearch(query: String, vararg keywords: String): Boolean {
    if (query.isBlank()) return false
    val cleanedQuery = query.trim().lowercase().replace("-", " ").replace("_", " ")
    val searchTerms = cleanedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (searchTerms.isEmpty()) return false
    val combinedTarget = keywords.joinToString(" ").lowercase().replace("-", " ").replace("_", " ")
    return searchTerms.any { term -> combinedTarget.contains(term) || (term.length > 2 && combinedTarget.split(" ").any { kw -> kw.startsWith(term) || term.startsWith(kw) }) }
}

data class HomeSectionDescriptor(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color
)

fun normalizeHomeSectionKey(key: String): String {
    return when (key.uppercase()) {
        "SPEED_DIAL" -> "speed_dial"
        "HERO_SPOTLIGHT", "CONTINUE_LISTENING" -> "continue_listening"
        "QUICK_ACTIONS" -> "quick_actions"
        "STATS", "LISTENING_STATS" -> "listening_stats"
        "MOST_PLAYED" -> "most_played"
        "PLAYLISTS", "MOST_PLAYED_PLAYLISTS" -> "most_played_playlists"
        "RECENTLY_PLAYED" -> "recently_played"
        "RECENTLY_ADDED" -> "recently_added"
        "ARTISTS", "TOP_ARTISTS" -> "top_artists"
        "ALBUMS", "TOP_ALBUMS" -> "top_albums"
        else -> key.lowercase()
    }
}

val ALL_HOME_SECTIONS = listOf(
    HomeSectionDescriptor("continue_listening", "Continue Listening", "Resume last active playback banner", Icons.Default.PlayCircle, Color(0xFFFF9100)),
    HomeSectionDescriptor("quick_actions", "Quick Actions", "Shuffle all tracks and quick navigation", Icons.Default.Shuffle, Color(0xFFFF4081)),
    HomeSectionDescriptor("listening_stats", "Listening Stats & Insights", "Minutes listened, day streak & top stats", Icons.Default.Insights, Color(0xFF00E676)),
    HomeSectionDescriptor("most_played", "Most Played / Heavy Rotation", "Your most frequently played songs", Icons.Default.Whatshot, Color(0xFFFF5252)),
    HomeSectionDescriptor("most_played_playlists", "Most Played Playlists", "Your custom and smart playlists", Icons.AutoMirrored.Filled.QueueMusic, Color(0xFF7C4DFF)),
    HomeSectionDescriptor("recently_played", "Recently Played", "Quick access to your most recently played tracks", Icons.Default.History, Color(0xFF2979FF)),
    HomeSectionDescriptor("recently_added", "Recently Added", "Freshly scanned & newly added songs", Icons.Default.NewReleases, Color(0xFFFFD700)),
    HomeSectionDescriptor("top_artists", "Top Artists", "Favorite artists avatar bubbles", Icons.Default.Person, Color(0xFFBB86FC)),
    HomeSectionDescriptor("top_albums", "Top Albums", "Cover art album grid & highlights", Icons.Default.Album, Color(0xFF1DE9B6))
)

@Composable
fun HomeScreenSectionsManagerCard(
    currentSections: List<String>,
    onUpdateSections: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeDescriptors = remember(currentSections) {
        currentSections.map { normalizeHomeSectionKey(it) }
            .distinct()
            .mapNotNull { normKey -> ALL_HOME_SECTIONS.find { it.key == normKey } }
    }
    val inactiveDescriptors = remember(activeDescriptors) {
        val activeKeys = activeDescriptors.map { it.key }.toSet()
        ALL_HOME_SECTIONS.filter { it.key !in activeKeys }
    }

    SettingsCardContainer(
        modifier = modifier.fillMaxWidth(),
        accentGlow = Color(0xFF7C4DFF)
    ) {
        // Header Row with glowing icon, title, count badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF7C4DFF).copy(alpha = 0.18f))
                        .border(BorderStroke(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f)), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DashboardCustomize,
                        contentDescription = null,
                        tint = Color(0xFF7C4DFF),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Home Screen Feed Sections",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Reorder, show, or hide feed sections across all home themes",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF7C4DFF).copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.35f))
            ) {
                Text(
                    text = "${activeDescriptors.size} / ${ALL_HOME_SECTIONS.size} Active",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF7C4DFF),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Quick Presets Row
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Quick Feed Presets",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoTextSubtle
            )
            val presets = remember {
                listOf(
                    "Default Full" to listOf("continue_listening", "quick_actions", "listening_stats", "most_played", "most_played_playlists", "recently_played", "recently_added", "top_artists", "top_albums"),
                    "Minimalist" to listOf("recently_played", "most_played_playlists"),
                    "Discovery & Stats" to listOf("listening_stats", "most_played", "top_artists", "top_albums"),
                    "Recents First" to listOf("recently_played", "recently_added", "most_played_playlists")
                )
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(presets, key = { it.first }, contentType = { "preset_chip" }) { (presetName, presetSections) ->
                    val activeKeys = activeDescriptors.map { it.key }
                    val isCurrentMatch = activeKeys == presetSections
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isCurrentMatch) Color(0xFF7C4DFF) else BentoCardPurple,
                        border = BorderStroke(1.dp, if (isCurrentMatch) Color(0xFF7C4DFF) else BentoBorder),
                        modifier = Modifier.clickable {
                            HapticUtils.performClickHaptic(context)
                            onUpdateSections(presetSections)
                        }
                    ) {
                        Text(
                            text = presetName,
                            fontSize = 11.5.sp,
                            fontWeight = if (isCurrentMatch) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCurrentMatch) BentoOnPrimary else BentoTextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        SettingsDivider()

        // Active Sections Reorderable List
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Active Sections (Display Order)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
                Text(
                    text = "Use arrows to reorder",
                    fontSize = 11.sp,
                    color = BentoTextSubtle
                )
            }

            if (activeDescriptors.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BentoCardPurple.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, BentoBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No sections active. Add sections below to populate the Home feed.",
                        fontSize = 12.sp,
                        color = BentoTextSubtle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeDescriptors.forEachIndexed { index, descriptor ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BentoCardPurple.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = descriptor.accentColor.copy(alpha = 0.18f),
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = descriptor.accentColor
                                            )
                                        }
                                    }

                                    Icon(
                                        imageVector = descriptor.icon,
                                        contentDescription = null,
                                        tint = descriptor.accentColor,
                                        modifier = Modifier.size(18.dp)
                                    )

                                    Column {
                                        Text(
                                            text = descriptor.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextPrimary
                                        )
                                        Text(
                                            text = descriptor.subtitle,
                                            fontSize = 10.5.sp,
                                            color = BentoTextSubtle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                HapticUtils.performClickHaptic(context)
                                                val updatedKeys = activeDescriptors.map { it.key }.toMutableList()
                                                val item = updatedKeys.removeAt(index)
                                                updatedKeys.add(index - 1, item)
                                                onUpdateSections(updatedKeys)
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowUp,
                                            contentDescription = "Move Up",
                                            tint = if (index > 0) BentoTextPrimary else BentoTextSubtle.copy(alpha = 0.4f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            if (index < activeDescriptors.size - 1) {
                                                HapticUtils.performClickHaptic(context)
                                                val updatedKeys = activeDescriptors.map { it.key }.toMutableList()
                                                val item = updatedKeys.removeAt(index)
                                                updatedKeys.add(index + 1, item)
                                                onUpdateSections(updatedKeys)
                                            }
                                        },
                                        enabled = index < activeDescriptors.size - 1,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Move Down",
                                            tint = if (index < activeDescriptors.size - 1) BentoTextPrimary else BentoTextSubtle.copy(alpha = 0.4f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            val updatedKeys = activeDescriptors.map { it.key }.toMutableList()
                                            updatedKeys.removeAt(index)
                                            onUpdateSections(updatedKeys)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VisibilityOff,
                                            contentDescription = "Hide Section",
                                            tint = Color(0xFFFF5252).copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Inactive / Hidden Sections Row
        if (inactiveDescriptors.isNotEmpty()) {
            SettingsDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Hidden Sections (Tap + to Enable)",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextSubtle
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(inactiveDescriptors, key = { it.key }, contentType = { "descriptor_chip" }) { descriptor ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = BentoCardPurple,
                                border = BorderStroke(1.dp, BentoBorder),
                                modifier = Modifier.clickable {
                                    HapticUtils.performClickHaptic(context)
                                    val updatedKeys = activeDescriptors.map { it.key }.toMutableList()
                                    updatedKeys.add(descriptor.key)
                                    onUpdateSections(updatedKeys)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add",
                                        tint = descriptor.accentColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = descriptor.title,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = BentoTextPrimary
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

val PRESET_ACCENT_COLORS = listOf(
    "CYAN" to Color(0xFF00E5FF),
    "PURPLE" to Color(0xFFBB86FC),
    "PINK" to Color(0xFFFF4081),
    "GREEN" to Color(0xFF00E676),
    "ORANGE" to Color(0xFFFF9100),
    "GOLD" to Color(0xFFFFD700),
    "BLUE" to Color(0xFF2979FF),
    "RED" to Color(0xFFFF5252),
    "TEAL" to Color(0xFF1DE9B6),
    "INDIGO" to Color(0xFF7C4DFF)
)

@Composable
fun SettingsScreen(
    folders: List<FolderInfo>,
    sleepTimerState: SleepTimerState,
    waveformStyle: String = "SNAKE",
    onSetWaveformStyle: (String) -> Unit = {},
    themeMode: String = "DARK",
    onSetThemeMode: (String) -> Unit = {},
    uiTheme: String = "MIDNIGHT_OBSIDIAN",
    onSetUiTheme: (String) -> Unit = {},
    displayScale: String = "DEFAULT",
    onSetDisplayScale: (String) -> Unit = {},
    homeScreenLayout: String = "BENTO_MOSAIC",
    onSetHomeScreenLayout: (String) -> Unit = {},
    nowPlayingLayout: String = "MODERN_BENTO",
    onSetNowPlayingLayout: (String) -> Unit = {},
    playlistsLayout: String = "GLOW_CARDS",
    onSetPlaylistsLayout: (String) -> Unit = {},
    isDynamicArtworkTheme: Boolean = false,
    onSetDynamicArtworkTheme: (Boolean) -> Unit = {},
    fetchOnlineArtworkHighRes: Boolean = false,
    onSetFetchOnlineArtworkHighRes: (Boolean) -> Unit = {},
    cacheLyricsEnabled: Boolean = true,
    onSetCacheLyricsEnabled: (Boolean) -> Unit = {},
    onClearLyricsCache: () -> Unit = {},
    cacheAlbumArtEnabled: Boolean = true,
    onSetCacheAlbumArtEnabled: (Boolean) -> Unit = {},
    onClearAlbumArtCache: () -> Unit = {},
    cacheSongArtWorkEnabled: Boolean = true,
    onSetCacheSongArtWorkEnabled: (Boolean) -> Unit = {},
    onClearSongArtCache: () -> Unit = {},
    lyricsCacheSize: String = "0 KB",
    albumArtCacheSize: String = "0 KB",
    songArtCacheSize: String = "0 KB",
    totalCacheSize: String = "0 KB",
    onRefreshCacheSizes: () -> Unit = {},
    onClearAllCaches: () -> Unit = {},
    databaseStorageTier: String = "EXPANDED",
    onSetDatabaseStorageTier: (String) -> Unit = {},
    databaseStorageStats: com.example.data.local.DatabaseStorageStats? = null,
    isOptimizingDatabase: Boolean = false,
    onOptimizeDatabase: ((reclaimedBytes: Long, message: String) -> Unit) -> Unit = { it(0L, "") },
    onExportDatabaseBackup: (((java.io.File?) -> Unit)) -> Unit = { it(null) },
    onImportDatabaseBackup: (String, (Boolean, String) -> Unit) -> Unit = { _, cb -> cb(false, "") },
    onRefreshDatabaseStats: () -> Unit = {},
    customEqBands: List<Float> = emptyList(),
    onSetCustomEqBands: (List<Float>) -> Unit = {},
    customBassBoost: Int = 0,
    onSetCustomBassBoost: (Int) -> Unit = {},
    customVirtualizer: Int = 0,
    onSetCustomVirtualizer: (Int) -> Unit = {},
    customAccentColor: Int = 0,
    onSetCustomAccentColor: (Int) -> Unit = {},
    accentColorPreset: String = "CYAN",
    onSetAccentColorPreset: (String) -> Unit = {},
    miniPlayerStyle: String = "FLOATING",
    onSetMiniPlayerStyle: (String) -> Unit = {},
    miniPlayerShowPrevious: Boolean = true,
    onSetMiniPlayerShowPrevious: (Boolean) -> Unit = {},
    miniPlayerShowNext: Boolean = true,
    onSetMiniPlayerShowNext: (Boolean) -> Unit = {},
    miniPlayerShowFavorite: Boolean = true,
    onSetMiniPlayerShowFavorite: (Boolean) -> Unit = {},
    gaplessPlayback: Boolean = true,
    onSetGaplessPlayback: (Boolean) -> Unit = {},
    normalizeAudio: Boolean = false,
    onSetNormalizeAudio: (Boolean) -> Unit = {},
    autoPlayOnHeadphoneConnection: Boolean = false,
    onSetAutoPlayOnHeadphoneConnection: (Boolean) -> Unit = {},
    autoPlayOnAppStartup: Boolean = false,
    onSetAutoPlayOnAppStartup: (Boolean) -> Unit = {},
    isTranscribeTranslateEnabled: Boolean = false,
    onSetTranscribeTranslateEnabled: (Boolean) -> Unit = {},
    hasUserApiKey: Boolean = false,
    maskedApiKey: String? = null,
    isKeyValidating: Boolean = false,
    keyValidationErrorMessage: String? = null,
    onSaveUserApiKey: (String, (Boolean, String?) -> Unit) -> Unit = { _, _ -> },
    onDeleteUserApiKey: () -> Unit = {},
    onClearKeyValidationError: () -> Unit = {},
    pauseOnZeroVolume: Boolean = false,
    onSetPauseOnZeroVolume: (Boolean) -> Unit = {},
    autoResumeOnVolumeIncrease: Boolean = false,
    onSetAutoResumeOnVolumeIncrease: (Boolean) -> Unit = {},
    ignoreAudioDuration: Int = 0,
    onSetIgnoreAudioDuration: (Int) -> Unit = {},
    fadeInOutMs: Int = 1000,
    onSetFadeInOutMs: (Int) -> Unit = {},
    fadeInMs: Int = 1000,
    onSetFadeInMs: (Int) -> Unit = {},
    fadeOutMs: Int = 1000,
    onSetFadeOutMs: (Int) -> Unit = {},
    crossfadeDurationSec: Int = 0,
    onSetCrossfadeDurationSec: (Int) -> Unit = {},
    shakeToSkip: Boolean = false,
    onSetShakeToSkip: (Boolean) -> Unit = {},
    keepScreenAwake: Boolean = false,
    onSetKeepScreenAwake: (Boolean) -> Unit = {},
    audioBalance: Float = 0.0f,
    onSetAudioBalance: (Float) -> Unit = {},
    equalizerPreset: String = "NORMAL",
    onSetEqualizerPreset: (String) -> Unit = {},
    lyricsAutoScroll: Boolean = true,
    onSetLyricsAutoScroll: (Boolean) -> Unit = {},
    reduceVolumeOnNotification: Boolean = true,
    onSetReduceVolumeOnNotification: (Boolean) -> Unit = {},
    pauseDuringCamera: Boolean = true,
    onSetPauseDuringCamera: (Boolean) -> Unit = {},
    scrobbleThresholdType: String = "PERCENTAGE",
    onSetScrobbleThresholdType: (String) -> Unit = {},
    scrobbleThresholdValue: Int = 50,
    onSetScrobbleThresholdValue: (Int) -> Unit = {},
    highRefreshRate: Boolean = true,
    onSetHighRefreshRate: (Boolean) -> Unit = {},
    alwaysShuffleMode: Boolean = false,
    onSetAlwaysShuffleMode: (Boolean) -> Unit = {},
    gesturePlaybackEnabled: Boolean = true,
    onSetGesturePlaybackEnabled: (Boolean) -> Unit = {},
    addToQueuePosition: String = "BOTTOM",
    onSetAddToQueuePosition: (String) -> Unit = {},
    playlistAddSongPosition: String = "BOTTOM",
    onSetPlaylistAddSongPosition: (String) -> Unit = {},
    homeSections: List<String> = emptyList(),
    onSetHomeSections: (List<String>) -> Unit = {},
    recentSearches: List<String> = emptyList(),
    onDeleteRecentSearch: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    onAddFolder: (path: String, name: String, isExcluded: Boolean) -> Unit = { _, _, _ -> },
    onRemoveFolder: (path: String) -> Unit = {},
    onStartSleepTimer: (minutes: Int, finishLastSong: Boolean) -> Unit = { _, _ -> },
    onStartSongCountTimer: (songs: Int) -> Unit = {},
    onAddSleepTimerMinutes: (minutes: Int) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onScanLibrary: () -> Unit = {},
    onQuickScanAndCleanLibrary: () -> Unit = {},
    scanProgress: com.example.scanner.ScanProgress = com.example.scanner.ScanProgress(),
    userAccount: com.example.auth.UserAccount? = null,
    effectiveDisplayName: String = "Music Lover",
    customAvatarUri: String? = null,
    syncStatus: com.example.data.sync.SyncStatus = com.example.data.sync.SyncStatus.Idle,
    backupStatus: com.example.data.sync.BackupStatus = com.example.data.sync.BackupStatus.Idle,
    lastSyncedTimestamp: Long = 0L,
    lastBackupTimestamp: Long = 0L,
    automaticBackupEnabled: Boolean = false,
    backupFrequency: com.example.data.sync.BackupFrequency = com.example.data.sync.BackupFrequency.WEEKLY,
    onToggleAutomaticBackup: (Boolean) -> Unit = {},
    onSelectFrequency: (com.example.data.sync.BackupFrequency) -> Unit = {},
    onTriggerSync: () -> Unit = {},
    onTriggerBackup: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenAuth: () -> Unit = {},
    onBackClick: () -> Unit = {},
    initialTargetSection: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedSection by remember {
        mutableStateOf(
            when (initialTargetSection?.uppercase()) {
                "AUDIO" -> SettingsSection.AUDIO
                "UI", "THEME" -> SettingsSection.UI
                "HOME", "FEED" -> SettingsSection.HOME_FEED
                "CACHE", "STORAGE" -> SettingsSection.CACHE
                "PLAYBACK", "TIMER" -> SettingsSection.PLAYBACK
                "LIBRARY", "FOLDERS" -> SettingsSection.LIBRARY
                "OTHER", "PRIVACY" -> SettingsSection.OTHER
                "CLOUD", "SYNC", "CLOUD_SYNC", "ACCOUNT" -> SettingsSection.CLOUD_SYNC
                else -> SettingsSection.ALL
            }
        )
    }

    val tabHistory = remember { mutableStateListOf<SettingsSection>() }
    var apiKeyInput by remember { mutableStateOf("") }
    var isEditingApiKey by remember { mutableStateOf(false) }
    var showKeyText by remember { mutableStateOf(false) }

    fun navigateToSection(newSection: SettingsSection) {
        if (selectedSection != newSection) {
            tabHistory.add(selectedSection)
            selectedSection = newSection
        }
    }

    var showAllInOneList by remember { mutableStateOf(false) }

    fun handleBack() {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
            return
        }
        if (showAllInOneList) {
            showAllInOneList = false
            return
        }
        if (tabHistory.isNotEmpty()) {
            val previous = tabHistory.removeAt(tabHistory.lastIndex)
            selectedSection = previous
        } else if (selectedSection != SettingsSection.ALL) {
            selectedSection = SettingsSection.ALL
        } else {
            onBackClick()
        }
    }

    BackHandler(enabled = true) {
        handleBack()
    }

    var selectedLayoutCustomizerScreen by remember { mutableStateOf("NOW_PLAYING") }
    var isPickingForExclusion by remember { mutableStateOf(false) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var showCustomTrackDialog by remember { mutableStateOf(false) }
    var showCustomMinutesDialog by remember { mutableStateOf(false) }
    var finishLastSongSettings by remember { mutableStateOf(sleepTimerState.finishLastSong) }
    var diagnosticsRefreshTrigger by remember { mutableIntStateOf(0) }
    var showHeadphonePermissionDialog by remember { mutableStateOf(false) }

    val bluetoothConnectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onSetAutoPlayOnHeadphoneConnection(true)
        } else {
            onSetAutoPlayOnHeadphoneConnection(false)
            android.widget.Toast.makeText(
                context,
                "Permission denied. Automatically play on headphone connection remains OFF.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    val listState = rememberLazyListState()

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            val storageType = parts.firstOrNull() ?: ""
            val subPath = if (parts.size > 1) parts[1] else ""

            val resolvedPath = when {
                storageType.equals("primary", ignoreCase = true) -> {
                    if (subPath.isNotBlank()) "/storage/emulated/0/$subPath" else "/storage/emulated/0"
                }
                storageType.startsWith("raw:") -> {
                    storageType.removePrefix("raw:").trimEnd('/')
                }
                storageType.startsWith("/") -> {
                    if (subPath.isNotBlank()) "$storageType/$subPath" else storageType
                }
                else -> {
                    if (subPath.isNotBlank()) "/storage/$storageType/$subPath" else "/storage/$storageType"
                }
            }

            val folderName = if (subPath.isNotBlank()) {
                subPath.substringAfterLast('/')
            } else {
                resolvedPath.substringAfterLast('/').ifBlank { "Selected Folder" }
            }

            onAddFolder(resolvedPath, folderName, isPickingForExclusion)
        }
    }

    // Custom Track Count Sleep Timer Dialog
    if (showCustomTrackDialog) {
        var trackInput by remember { mutableStateOf("5") }
        Dialog(onDismissRequest = { showCustomTrackDialog = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = BentoCardSurface,
                border = BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFFF9100).copy(alpha = 0.7f),
                            BentoBorder.copy(alpha = 0.4f)
                        )
                    )
                ),
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF9100).copy(alpha = 0.16f))
                            .border(BorderStroke(1.2.dp, Color(0xFFFF9100).copy(alpha = 0.4f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Numbers, contentDescription = null, tint = Color(0xFFFF9100), modifier = Modifier.size(26.dp))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Track-Based Sleep Timer",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Stop playback automatically after playing a specific count of complete songs.",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }

                    // Quick Track Count Presets
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(1, 3, 5, 10, 20).forEach { count ->
                            val isSelected = trackInput == count.toString()
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) Color(0xFFFF9100) else BentoCardPurple,
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFFFF9100) else BentoBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        HapticUtils.performClickHaptic(context)
                                        trackInput = count.toString()
                                    }
                            ) {
                                Text(
                                    text = "$count",
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else BentoTextPrimary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = trackInput,
                        onValueChange = { trackInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Or enter custom track count") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF9100),
                            focusedLabelColor = Color(0xFFFF9100),
                            unfocusedContainerColor = BentoCardPurple.copy(alpha = 0.5f),
                            focusedContainerColor = BentoCardPurple
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCustomTrackDialog = false },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = BentoTextSecondary)
                        }
                        Button(
                            onClick = {
                                val count = trackInput.toIntOrNull() ?: 5
                                if (count > 0) {
                                    onStartSongCountTimer(count)
                                    showCustomTrackDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9100), contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Set Timer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Custom Minutes Sleep Timer Dialog
    if (showCustomMinutesDialog) {
        var minInput by remember { mutableStateOf("30") }
        Dialog(onDismissRequest = { showCustomMinutesDialog = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = BentoCardSurface,
                border = BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            BentoPrimary.copy(alpha = 0.7f),
                            BentoBorder.copy(alpha = 0.4f)
                        )
                    )
                ),
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(BentoPrimary.copy(alpha = 0.16f))
                            .border(BorderStroke(1.2.dp, BentoPrimary.copy(alpha = 0.4f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(26.dp))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Time-Based Sleep Timer",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Turn off music automatically after the specified duration.",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }

                    // Quick Minutes Presets
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(listOf(10, 15, 30, 45, 60, 90), key = { it }, contentType = { "min_chip" }) { mins ->
                            val isSelected = minInput == mins.toString()
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) BentoPrimary else BentoCardPurple,
                                border = BorderStroke(1.dp, if (isSelected) BentoPrimary else BentoBorder),
                                modifier = Modifier.clickable {
                                    HapticUtils.performClickHaptic(context)
                                    minInput = mins.toString()
                                }
                            ) {
                                Text(
                                    text = "${mins}m",
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) BentoOnPrimary else BentoTextPrimary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = minInput,
                        onValueChange = { minInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Or enter custom minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BentoPrimary,
                            focusedLabelColor = BentoPrimary,
                            unfocusedContainerColor = BentoCardPurple.copy(alpha = 0.5f),
                            focusedContainerColor = BentoCardPurple
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardPurple.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                HapticUtils.performClickHaptic(context)
                                finishLastSongSettings = !finishLastSongSettings
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Checkbox(
                                checked = finishLastSongSettings,
                                onCheckedChange = { finishLastSongSettings = it },
                                colors = CheckboxDefaults.colors(checkedColor = BentoPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Finish last track before stopping",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Wait until the currently playing song completes",
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCustomMinutesDialog = false },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = BentoTextSecondary)
                        }
                        Button(
                            onClick = {
                                val mins = minInput.toIntOrNull() ?: 30
                                if (mins > 0) {
                                    onStartSleepTimer(mins, finishLastSongSettings)
                                    showCustomMinutesDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Set Timer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showHeadphonePermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showHeadphonePermissionDialog = false
                onSetAutoPlayOnHeadphoneConnection(false)
            },
            title = {
                Text(
                    text = "Nearby Devices Permission",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                Text(
                    text = "To detect when wireless Bluetooth headphones or earbuds connect and automatically start or resume music, Android requires permission to find nearby devices.",
                    color = BentoTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showHeadphonePermissionDialog = false
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            bluetoothConnectLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            onSetAutoPlayOnHeadphoneConnection(true)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("Grant Permission", color = BentoOnPrimary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showHeadphonePermissionDialog = false
                        onSetAutoPlayOnHeadphoneConnection(false)
                    }
                ) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            }
        )
    }

    // Circle-based RGB Color Picker Dialog
    if (showColorPickerDialog) {
        var redVal by remember { mutableFloatStateOf(if (customAccentColor != 0) Color(customAccentColor).red else 0.0f) }
        var greenVal by remember { mutableFloatStateOf(if (customAccentColor != 0) Color(customAccentColor).green else 0.9f) }
        var blueVal by remember { mutableFloatStateOf(if (customAccentColor != 0) Color(customAccentColor).blue else 1.0f) }

        val pickedColor = Color(redVal, greenVal, blueVal)

        Dialog(onDismissRequest = { showColorPickerDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = BentoCardSurface,
                border = BorderStroke(1.dp, pickedColor.copy(alpha = 0.6f)),
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Custom RGB Color Picker",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        textAlign = TextAlign.Center
                    )

                    // Big Circular Color Preview Swatch with ambient glow
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(pickedColor)
                            .border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = Color.Black.copy(alpha = 0.5f),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    // Hex Preview Badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BentoCardPurple,
                        border = BorderStroke(1.dp, pickedColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = String.format("#%02X%02X%02X", (redVal * 255).toInt(), (greenVal * 255).toInt(), (blueVal * 255).toInt()),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = pickedColor,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    // Red Slider
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Red", fontSize = 13.sp, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFFF5252).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "${(redVal * 255).toInt()}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF5252),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Slider(
                            value = redVal,
                            onValueChange = { redVal = it },
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFFF5252), activeTrackColor = Color(0xFFFF5252))
                        )
                    }

                    // Green Slider
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Green", fontSize = 13.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF00E676).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "${(greenVal * 255).toInt()}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E676),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Slider(
                            value = greenVal,
                            onValueChange = { greenVal = it },
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF00E676), activeTrackColor = Color(0xFF00E676))
                        )
                    }

                    // Blue Slider
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Blue", fontSize = 13.sp, color = Color(0xFF2979FF), fontWeight = FontWeight.Bold)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF2979FF).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "${(blueVal * 255).toInt()}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2979FF),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Slider(
                            value = blueVal,
                            onValueChange = { blueVal = it },
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF2979FF), activeTrackColor = Color(0xFF2979FF))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onSetCustomAccentColor(0)
                                showColorPickerDialog = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset")
                        }
                        Button(
                            onClick = {
                                onSetCustomAccentColor(pickedColor.toArgb())
                                onSetDynamicArtworkTheme(false)
                                showColorPickerDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = pickedColor, contentColor = Color.White),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Apply")
                        }
                    }
                }
            }
        }
    }

    val isSearching = searchQuery.isNotBlank()

    val audioMatches = matchesSettingsSearch(
        searchQuery,
        "audio", "sound", "normalize", "normalization", "normalize audio", "loudness", "crossfade", "fade", "fading", "gapless", "volume", "balance", "channel",
        "stereo", "pan", "left", "right", "equalizer", "eq", "bands", "bass", "bassboost", "boost",
        "transcribe", "translate", "tamil", "tanglish", "hindi", "malayalam", "vocal", "lyrics translation",
        "virtualizer", "3d", "spatial", "preset", "normal", "rock", "pop", "classical", "jazz",
        "dance", "electronic", "vocal", "acoustic", "flat", "notification", "duck", "ducking",
        "earbud", "headphone", "speaker", "pause on zero", "auto resume", "decibel", "hz"
    )

    val uiMatches = matchesSettingsSearch(
        searchQuery,
        "ui", "theme", "themes", "dark", "light", "amoled", "oled", "pure black", "color", "accent",
        "palette", "custom color", "picker", "dynamic", "artwork theme", "layout", "now playing",
        "playlist", "home layout", "bento", "mosaic", "grid", "compact", "cards", "high res",
        "online artwork", "cover", "lyrics", "lyrics autoscroll", "autoscroll", "synced lyrics",
        "waveform", "visualizer", "snake", "bars", "wave", "pulse", "refresh rate", "120hz", "60hz",
        "fluid", "mini player", "floating", "docked", "pill", "visual", "appearance", "display",
        "font", "size", "neon", "amber", "emerald", "indigo", "glassmorphism", "cyberpunk",
        "retro", "synthwave", "material you", "aurora", "custom", "radius", "corner"
    )

    val homeFeedMatches = matchesSettingsSearch(
        searchQuery,
        "home", "feed", "section", "sections", "category", "categories", "order", "reorder", "show",
        "hide", "stats", "artist", "artists", "speed dial", "most played", "recently added",
        "recently played", "top albums", "top artists", "smart playlist", "continue listening",
        "quick action", "quick actions", "dashboard", "spotlight", "minimal", "minimalist", "preset",
        "recents first", "discovery", "top"
    )

    val playbackMatches = matchesSettingsSearch(
        searchQuery,
        "playback", "controls", "timer", "sleep", "sleep timer", "countdown", "stop timer", "minute",
        "minutes", "finish last song", "track count", "song count", "shake", "shake to skip",
        "sensor", "gesture", "gestures", "swipe", "swipe to skip", "tap to play", "album art gesture", "shuffle", "always shuffle", "screen", "awake", "keep screen awake",
        "scrobble", "duration", "percentage", "completion", "threshold", "resume", "bluetooth",
        "headset", "headphone", "earbud", "unplug", "loop", "repeat", "autoplay", "startup", "app startup", "track", "count", "queue", "add to queue", "add to queue position", "playlist", "add to playlist"
    )

    val cacheMatches = matchesSettingsSearch(
        searchQuery,
        "cache", "offline", "storage", "lyrics", "lyrics cache", "artwork", "artwork cache", "album",
        "album art", "image", "covers", "song artwork", "mb", "gb", "kb", "clear", "clear cache",
        "clear all", "disk", "memory", "cleanup", "download", "high res", "database", "sqlite", "room", "storage tier", "wal", "optimize", "backup", "restore"
    )

    val libraryMatches = matchesSettingsSearch(
        searchQuery,
        "library", "folder", "folders", "directory", "directories", "music directory", "scan",
        "rescan", "media scan", "clean", "cleaner", "tag cleaner", "prune", "exclude", "block",
        "include", "path", "storage", "filter", "ignore", "duration", "ringtone", "short",
        "short audio", "seconds", "add folder", "remove folder"
    )

    val otherMatches = matchesSettingsSearch(
        searchQuery,
        "search", "history", "recent", "recent searches", "privacy", "diagnostics", "crash", "log",
        "logs", "crash report", "report", "clear history", "share", "security", "bug", "about",
        "version", "system health", "build", "other"
    )

    val cloudSyncMatches = matchesSettingsSearch(
        searchQuery,
        "cloud", "sync", "synchronize", "backup", "restore", "account", "login", "sign in", "sign out",
        "firebase", "google", "profile", "cross device", "favorites", "playlists", "history", "avatar"
    )

    val anyMatches = audioMatches || uiMatches || homeFeedMatches || playbackMatches || cacheMatches || libraryMatches || otherMatches || cloudSyncMatches

    val isTopLevelHub = selectedSection == SettingsSection.ALL && !isSearching && !showAllInOneList

    val showAudio = if (isSearching) audioMatches else (!isTopLevelHub && (selectedSection == SettingsSection.ALL || selectedSection == SettingsSection.AUDIO))
    val showUi = if (isSearching) uiMatches else (!isTopLevelHub && (selectedSection == SettingsSection.ALL || selectedSection == SettingsSection.UI))
    val showHomeFeed = if (isSearching) homeFeedMatches else (!isTopLevelHub && (selectedSection == SettingsSection.ALL || selectedSection == SettingsSection.HOME_FEED))
    val showPlayback = if (isSearching) playbackMatches else (!isTopLevelHub && (selectedSection == SettingsSection.ALL || selectedSection == SettingsSection.PLAYBACK))
    val showCache = if (isSearching) cacheMatches else (!isTopLevelHub && (selectedSection == SettingsSection.ALL || selectedSection == SettingsSection.CACHE))
    val showLibrary = if (isSearching) libraryMatches else (!isTopLevelHub && (selectedSection == SettingsSection.ALL || selectedSection == SettingsSection.LIBRARY))
    val showOther = if (isSearching) otherMatches else (!isTopLevelHub && (selectedSection == SettingsSection.ALL || selectedSection == SettingsSection.OTHER))
    val showCloudSync = if (isSearching) cloudSyncMatches else (!isTopLevelHub && (selectedSection == SettingsSection.ALL || selectedSection == SettingsSection.CLOUD_SYNC))

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // User Account Tile / Card in Settings
            item(key = "settings_account_tile") {
                val avatarPhoto = customAvatarUri ?: userAccount?.photoUrl
                Surface(
                    onClick = {
                        if (userAccount != null) onOpenAccount() else onOpenAuth()
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.16f),
                            border = BorderStroke(1.5.dp, BentoPrimary.copy(alpha = 0.4f)),
                            modifier = Modifier.size(48.dp)
                        ) {
                            if (!avatarPhoto.isNullOrBlank()) {
                                coil.compose.AsyncImage(
                                    model = avatarPhoto,
                                    contentDescription = "Profile Photo",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else if (userAccount != null) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = (effectiveDisplayName.firstOrNull() ?: 'U').uppercase(),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary
                                    )
                                }
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = "Profile Photo",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (userAccount != null) effectiveDisplayName else "Account & Profile",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (userAccount != null) BentoPrimary.copy(alpha = 0.15f) else Color(0xFF00E676).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (userAccount != null) userAccount.friendlyProviderName else "Offline Mode",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (userAccount != null) BentoPrimary else Color(0xFF00E676),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = userAccount?.email ?: "Tap to sign in or create an account",
                                fontSize = 12.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = BentoTextSubtle
                        )
                    }
                }
            }

            // 1. Futuristic Hero Dashboard Header
            item(key = "settings_hero_dashboard") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                BentoPrimary.copy(alpha = 0.5f),
                                BentoBorder.copy(alpha = 0.35f),
                                BentoPrimary.copy(alpha = 0.2f)
                            )
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        BentoPrimary.copy(alpha = 0.04f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Top Bar: Back Button, Title, System Subtitle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    handleBack()
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = BentoTextPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Settings",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                            }
                        }

                        // Elegant Frosted Search Box
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = BentoCardSurface,
                            border = BorderStroke(
                                1.dp,
                                if (searchQuery.isNotBlank()) BentoPrimary.copy(alpha = 0.5f) else BentoBorder.copy(alpha = 0.30f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = if (searchQuery.isNotBlank()) BentoPrimary else BentoTextSubtle,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text(
                                            "Search equalizer, sleep timer, cache, themes...",
                                            fontSize = 12.5.sp,
                                            color = BentoTextSubtle
                                        )
                                    },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedTextColor = BentoTextPrimary,
                                        unfocusedTextColor = BentoTextPrimary,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = BentoTextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!isSearching) {
                // 2. Subtle & Elegant Category Navigation Chips (Quick Navigation Hub)
                item(key = "settings_category_chips") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(SettingsSection.entries, key = { it.name }, contentType = { "section_chip" }) { section ->
                            val isSelected = selectedSection == section && searchQuery.isEmpty()
                            val sectionAccent = when (section) {
                                SettingsSection.ALL -> BentoPrimary
                                SettingsSection.AUDIO -> Color(0xFF00E5FF)
                                SettingsSection.UI -> Color(0xFFBB86FC)
                                SettingsSection.HOME_FEED -> Color(0xFF7C4DFF)
                                SettingsSection.PLAYBACK -> Color(0xFFFF9100)
                                SettingsSection.CACHE -> Color(0xFF00E676)
                                SettingsSection.LIBRARY -> Color(0xFF2979FF)
                                SettingsSection.OTHER -> Color(0xFFFF5252)
                                SettingsSection.CLOUD_SYNC -> Color(0xFF00E5FF)
                            }
                            val chipBg = if (isSelected) sectionAccent.copy(alpha = 0.12f) else BentoCardSurface
                            val chipBorder = if (isSelected) sectionAccent.copy(alpha = 0.38f) else BentoBorder.copy(alpha = 0.28f)

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = chipBg,
                                border = BorderStroke(1.dp, chipBorder),
                                modifier = Modifier.clickable {
                                    HapticUtils.performClickHaptic(context)
                                    searchQuery = ""
                                    navigateToSection(section)
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Icon(
                                        imageVector = section.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) sectionAccent else BentoTextSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = section.title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) BentoTextPrimary else BentoTextSecondary
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(sectionAccent)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (anyMatches) {
                item(key = "settings_search_results_badge") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BentoPrimary.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Showing results for \"$searchQuery\"",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BentoTextPrimary
                                )
                            }
                            TextButton(
                                onClick = { searchQuery = "" },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Clear", fontSize = 12.sp, color = BentoPrimary)
                            }
                        }
                    }
                }
            }

            if (isTopLevelHub) {
                item(key = "settings_hub_title") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "CATEGORIES",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "8 categories",
                            fontSize = 11.sp,
                            color = BentoTextSubtle
                        )
                    }
                }

                items(SETTINGS_CATEGORY_CARD_ITEMS, key = { it.section.name }) { catItem ->
                    val sec = catItem.section
                    val title = catItem.title
                    val subtitle = catItem.subtitle
                    val icon = catItem.icon
                    val accent = catItem.accentColor
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            navigateToSection(sec)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(accent.copy(alpha = 0.14f))
                                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.30f)), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary,
                                    lineHeight = 15.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = BentoTextSubtle,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                item(key = "settings_hub_view_all_footer") {
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            showAllInOneList = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = BentoCardSurface.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Browse All Settings Continuously",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoPrimary
                            )
                        }
                    }
                }
            } else if (!isSearching && selectedSection != SettingsSection.ALL) {
                item(key = "settings_category_breadcrumb_header") {
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            handleBack()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to categories",
                                tint = BentoPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Settings",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = BentoTextSubtle,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = selectedSection.title,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoPrimary
                            )
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 1: AUDIO & SOUND
            // ==========================================
            if (showAudio) {
                item(key = "settings_audio_header") {
                    SettingsSectionHeader(
                        title = "Audio Engine & Sound Effects",
                        subtitle = "Crossfade transitions, volume fading, channel balance & 5-band equalizer",
                        kicker = "AUDIO & SOUND ENGINE",
                        icon = Icons.Default.Equalizer,
                        accentColor = Color(0xFF00E5FF)
                    )
                }

                item(key = "settings_audio_card") {
                    SettingsCardContainer(accentGlow = Color(0xFF00E5FF)) {
                        // Crossfade Duration Slider
                        SettingsSliderRow(
                            title = "Crossfade Audio Duration",
                            subtitle = "Smoothly blend tracks into one another during playback transitions",
                            value = crossfadeDurationSec.toFloat(),
                            onValueChange = { onSetCrossfadeDurationSec(it.toInt()) },
                            valueRange = 0f..12f,
                            steps = 11,
                            valueDisplay = if (crossfadeDurationSec == 0) "Off" else "${crossfadeDurationSec}s",
                            icon = Icons.Default.Shuffle,
                            accentColor = Color(0xFF00E5FF)
                        )

                        SettingsDivider()

                        // Play Fade-In Duration
                        SettingsSliderRow(
                            title = "Play Fade-In Duration",
                            subtitle = "Gradually ramp up track volume when starting or resuming playback",
                            value = fadeInMs.toFloat(),
                            onValueChange = { onSetFadeInMs(it.toInt()) },
                            valueRange = 0f..5000f,
                            steps = 9,
                            valueDisplay = if (fadeInMs == 0) "Instant" else "${fadeInMs}ms",
                            icon = Icons.Default.VolumeUp,
                            accentColor = Color(0xFF00E5FF)
                        )

                        SettingsDivider()

                        // Pause Fade-Out Duration
                        SettingsSliderRow(
                            title = "Pause Fade-Out Duration",
                            subtitle = "Softly lower volume when pausing to eliminate abrupt audio cuts",
                            value = fadeOutMs.toFloat(),
                            onValueChange = { onSetFadeOutMs(it.toInt()) },
                            valueRange = 0f..5000f,
                            steps = 9,
                            valueDisplay = if (fadeOutMs == 0) "Instant" else "${fadeOutMs}ms",
                            icon = Icons.Default.VolumeDown,
                            accentColor = Color(0xFF00E5FF)
                        )

                        SettingsDivider()

                        // Pause on Zero Volume
                        SettingsToggleRow(
                            title = "Pause on Zero Volume",
                            subtitle = "Automatically pause media playback when device volume drops to 0%",
                            checked = pauseOnZeroVolume,
                            onCheckedChange = onSetPauseOnZeroVolume,
                            icon = Icons.Default.VolumeMute,
                            accentColor = Color(0xFF00E5FF)
                        )

                        SettingsDivider()

                        // Auto Resume on Volume Increase
                        SettingsToggleRow(
                            title = "Auto Resume on Volume Increase",
                            subtitle = "Automatically resume music when raising volume back above 0%",
                            checked = autoResumeOnVolumeIncrease,
                            onCheckedChange = onSetAutoResumeOnVolumeIncrease,
                            icon = Icons.Default.PlayArrow,
                            accentColor = Color(0xFF00E5FF)
                        )

                        SettingsDivider()

                        // Gapless Playback
                        SettingsToggleRow(
                            title = "Gapless Audio Playback",
                            subtitle = "Eliminate silent gaps between live concert albums and progressive tracks",
                            checked = gaplessPlayback,
                            onCheckedChange = onSetGaplessPlayback,
                            icon = Icons.Default.MusicNote,
                            accentColor = Color(0xFF00E5FF)
                        )

                        SettingsDivider()

                        // Normalize Audio
                        SettingsToggleRow(
                            title = "Normalize Audio",
                            subtitle = "Balance volume levels across tracks to prevent sudden loudness differences",
                            checked = normalizeAudio,
                            onCheckedChange = onSetNormalizeAudio,
                            icon = Icons.Default.GraphicEq,
                            accentColor = Color(0xFF00E5FF)
                        )

                        SettingsDivider()

                        // Duck Volume for Notifications
                        SettingsToggleRow(
                            title = "Duck Volume for Notifications",
                            subtitle = "Briefly lower music volume when notification chimes or alerts play",
                            checked = reduceVolumeOnNotification,
                            onCheckedChange = onSetReduceVolumeOnNotification,
                            icon = Icons.Default.NotificationsActive,
                            accentColor = Color(0xFF00E5FF)
                        )

                        SettingsDivider()

                        // Auto-Pause for Camera & System Audio
                        SettingsToggleRow(
                            title = "Auto-Pause for Camera & System Audio",
                            subtitle = "Cleanly pauses playback when camera or other audio apps open, and immediately resumes when camera is closed to prevent stuttering",
                            checked = pauseDuringCamera,
                            onCheckedChange = onSetPauseDuringCamera,
                            icon = Icons.Default.PhotoCamera,
                            accentColor = Color(0xFF00E5FF)
                        )

                        SettingsDivider()

                        // Transcribe / Translate Lyrics Feature
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Enable Transcribe/Translate Lyrics Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f, fill = false),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Translate,
                                            contentDescription = null,
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "Enable Transcribe/Translate Lyrics",
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextPrimary
                                        )
                                        Text(
                                            text = if (isTranscribeTranslateEnabled) "Transcribe vocals or translate lyrics via Gemini AI" else "Default OFF — no Gemini API requests",
                                            fontSize = 11.5.sp,
                                            color = BentoTextSecondary
                                        )
                                    }
                                }

                                Switch(
                                    checked = isTranscribeTranslateEnabled,
                                    onCheckedChange = { enable ->
                                        HapticUtils.performClickHaptic(context)
                                        if (enable) {
                                            if (hasUserApiKey) {
                                                onSetTranscribeTranslateEnabled(true)
                                            } else {
                                                // Prompt user to enter their API key; keep toggle OFF until validly saved
                                                isEditingApiKey = true
                                                onSetTranscribeTranslateEnabled(false)
                                            }
                                        } else {
                                            onSetTranscribeTranslateEnabled(false)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.Black,
                                        checkedTrackColor = Color(0xFF00E5FF),
                                        uncheckedThumbColor = BentoTextSubtle,
                                        uncheckedTrackColor = BentoBorder
                                    )
                                )
                            }

                            // Gemini API Key Configuration Area (Shown when enabled, or when user opens input)
                            if (isTranscribeTranslateEnabled || isEditingApiKey) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = BentoCardSurface,
                                    border = BorderStroke(1.dp, if (hasUserApiKey && !isEditingApiKey) Color(0xFF00E5FF).copy(alpha = 0.35f) else BentoBorder.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (hasUserApiKey && !isEditingApiKey) {
                                            // Configured state without exposing the full key
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.VpnKey,
                                                        contentDescription = null,
                                                        tint = Color(0xFF00E5FF),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Column {
                                                        Text(
                                                            text = "Gemini API Key",
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = BentoTextPrimary
                                                        )
                                                        Text(
                                                            text = maskedApiKey ?: "••••••••••••",
                                                            fontSize = 11.5.sp,
                                                            color = BentoTextSecondary
                                                        )
                                                    }
                                                }

                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xFF00E676).copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "Configured",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF00E676),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            // Action Buttons: Edit / Replace Key and Delete Key
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedButton(
                                                    onClick = {
                                                        apiKeyInput = ""
                                                        isEditingApiKey = true
                                                        onClearKeyValidationError()
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    border = BorderStroke(1.dp, BentoBorder)
                                                ) {
                                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp), tint = BentoTextPrimary)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Edit / Replace", fontSize = 11.sp, color = BentoTextPrimary)
                                                }

                                                OutlinedButton(
                                                    onClick = {
                                                        onDeleteUserApiKey()
                                                        isEditingApiKey = false
                                                        apiKeyInput = ""
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f))
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFFFF5252))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Delete Key", fontSize = 11.sp, color = Color(0xFFFF5252))
                                                }
                                            }
                                        } else {
                                            // Input / Replace Key Field
                                            Text(
                                                text = if (isEditingApiKey && hasUserApiKey) "Replace Gemini API Key" else "Configure Gemini API Key",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoTextPrimary
                                            )
                                            Text(
                                                text = "Enter your Google Gemini API key to enable lyrics transcription and translation. Your key is stored securely on device (AES-256 GCM) and is never synchronized to Firestore or cloud backup.",
                                                fontSize = 11.sp,
                                                color = BentoTextSecondary
                                            )

                                            OutlinedTextField(
                                                value = apiKeyInput,
                                                onValueChange = {
                                                    apiKeyInput = it
                                                    if (keyValidationErrorMessage != null) {
                                                        onClearKeyValidationError()
                                                    }
                                                },
                                                label = { Text("Gemini API Key", fontSize = 12.sp) },
                                                placeholder = { Text("AIzaSy...", fontSize = 12.sp, color = BentoTextSubtle) },
                                                singleLine = true,
                                                visualTransformation = if (showKeyText) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                                trailingIcon = {
                                                    IconButton(onClick = { showKeyText = !showKeyText }, modifier = Modifier.size(28.dp)) {
                                                        Icon(
                                                            imageVector = if (showKeyText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                            contentDescription = if (showKeyText) "Hide key" else "Show key",
                                                            tint = BentoTextSecondary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                isError = keyValidationErrorMessage != null,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF00E5FF),
                                                    unfocusedBorderColor = BentoBorder,
                                                    focusedLabelColor = Color(0xFF00E5FF),
                                                    errorBorderColor = Color(0xFFFF5252)
                                                )
                                            )

                                            if (keyValidationErrorMessage != null) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color(0xFFFF5252).copy(alpha = 0.15f),
                                                    border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(14.dp))
                                                        Text(
                                                            text = keyValidationErrorMessage ?: "",
                                                            fontSize = 11.sp,
                                                            color = BentoTextPrimary,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        IconButton(onClick = onClearKeyValidationError, modifier = Modifier.size(20.dp)) {
                                                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = BentoTextSecondary, modifier = Modifier.size(12.dp))
                                                        }
                                                    }
                                                }
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (isEditingApiKey && hasUserApiKey) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            isEditingApiKey = false
                                                            apiKeyInput = ""
                                                            onClearKeyValidationError()
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                        shape = RoundedCornerShape(8.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                                    ) {
                                                        Text("Cancel", fontSize = 11.5.sp, color = BentoTextSecondary)
                                                    }
                                                }

                                                Button(
                                                    onClick = {
                                                        onSaveUserApiKey(apiKeyInput.trim()) { success, _ ->
                                                            if (success) {
                                                                isEditingApiKey = false
                                                                apiKeyInput = ""
                                                            }
                                                        }
                                                    },
                                                    enabled = apiKeyInput.isNotBlank() && !isKeyValidating,
                                                    modifier = Modifier.weight(1.2f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    if (isKeyValidating) {
                                                        CircularProgressIndicator(
                                                            color = Color.Black,
                                                            strokeWidth = 2.dp,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Validating...", fontSize = 11.5.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                                    } else {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Save & Validate Key", fontSize = 11.5.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        SettingsDivider()

                        // L/R Audio Balance with Stereo Bar Indicator
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f, fill = false),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Headphones, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                    }
                                    Column {
                                        Text("Stereo Channel Balance", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                        Text("Adjust relative output between left and right channels", fontSize = 11.5.sp, color = BentoTextSecondary)
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (kotlin.math.abs(audioBalance) > 0.05f) {
                                        Surface(
                                            onClick = {
                                                HapticUtils.performClickHaptic(context)
                                                onSetAudioBalance(0f)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = BentoCardPurple,
                                            border = BorderStroke(1.dp, BentoBorder)
                                        ) {
                                            Text(
                                                text = "Reset",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = BentoTextSecondary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF00E5FF).copy(alpha = 0.18f),
                                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = when {
                                                audioBalance < -0.05f -> "Left ${(-audioBalance * 100).toInt()}%"
                                                audioBalance > 0.05f -> "Right ${(audioBalance * 100).toInt()}%"
                                                else -> "Centered"
                                            },
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00E5FF),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // Visual Stereo Channel Balance Gauge
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = BentoCardSurface,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "L (Left)",
                                            fontSize = 11.sp,
                                            fontWeight = if (audioBalance < -0.05f) FontWeight.Bold else FontWeight.Medium,
                                            color = if (audioBalance < -0.05f) Color(0xFF00E5FF) else BentoTextSecondary
                                        )
                                        Text(
                                            text = "• CENTER •",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = if (kotlin.math.abs(audioBalance) <= 0.05f) Color(0xFF00E5FF) else BentoTextSubtle
                                        )
                                        Text(
                                            text = "R (Right)",
                                            fontSize = 11.sp,
                                            fontWeight = if (audioBalance > 0.05f) FontWeight.Bold else FontWeight.Medium,
                                            color = if (audioBalance > 0.05f) Color(0xFF00E5FF) else BentoTextSecondary
                                        )
                                    }

                                    Slider(
                                        value = audioBalance,
                                        onValueChange = { raw ->
                                            val snapped = if (kotlin.math.abs(raw) < 0.06f) 0f else raw
                                            onSetAudioBalance(snapped)
                                        },
                                        valueRange = -1.0f..1.0f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF00E5FF),
                                            activeTrackColor = Color(0xFF00E5FF),
                                            inactiveTrackColor = BentoCardPurple.copy(alpha = 0.6f)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        SettingsDivider()

                        // Equalizer Visualizer & Bands & Presets
                        EqualizerVisualizer(
                            currentPreset = equalizerPreset,
                            customBands = customEqBands,
                            bassBoostStrength = customBassBoost,
                            virtualizerStrength = customVirtualizer,
                            onPresetChange = onSetEqualizerPreset,
                            onBandsChange = onSetCustomEqBands,
                            onBassBoostChange = onSetCustomBassBoost,
                            onVirtualizerChange = onSetCustomVirtualizer
                        )
                    }
                }
            }

            // ==========================================
            // SECTION 2: UI & THEMES
            // ==========================================
            if (showUi) {
                item(key = "settings_ui_header") {
                    SettingsSectionHeader(
                        title = "Visual & Display Studio",
                        subtitle = "Curated aesthetics, screen architecture, accent palettes & refresh rate",
                        kicker = "VISUAL & DISPLAY STUDIO",
                        icon = Icons.Default.Palette,
                        accentColor = Color(0xFFBB86FC)
                    )
                }

                item(key = "settings_ui_card") {
                    SettingsCardContainer(accentGlow = Color(0xFFBB86FC)) {
                        // 1. Curated UI Themes
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .padding(end = 8.dp)
                                ) {
                                    Text("Curated Themes", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                    Text("Switch high-fidelity palettes with bespoke accent gradients", fontSize = 11.5.sp, color = BentoTextSecondary)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFBB86FC).copy(alpha = 0.18f),
                                    border = BorderStroke(1.dp, Color(0xFFBB86FC).copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = AppUiTheme.fromId(uiTheme).displayName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFBB86FC),
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }

                            val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
                            val compatibleThemes = remember(themeMode, isSystemDark, uiTheme) {
                                val list = com.example.ui.theme.AppUiTheme.getCompatibleThemes(themeMode, isSystemDark)
                                val currentTheme = com.example.ui.theme.AppUiTheme.fromId(uiTheme)
                                if (list.none { it.id.equals(currentTheme.id, ignoreCase = true) }) {
                                    listOf(currentTheme) + list
                                } else {
                                    list
                                }
                            }

                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                            ) {
                                items(compatibleThemes, key = { it.id }, contentType = { "theme_preview" }) { theme ->
                                    val isSelected = uiTheme.equals(theme.id, ignoreCase = true)
                                    ThemePreviewCard(
                                        theme = theme,
                                        isSelected = isSelected,
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onSetUiTheme(theme.id)
                                        }
                                    )
                                }
                            }
                        }

                        SettingsDivider()

                        // 2. Screen Layout Architecture
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column {
                                Text("Screen Layout Architecture", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                Text("Select dedicated UI wireframes for player and navigation screens", fontSize = 11.5.sp, color = BentoTextSecondary)
                            }

                            // Target Screen Selector Pills
                            SettingsSegmentedSelector(
                                options = listOf(
                                    "NOW_PLAYING" to "Now Playing",
                                    "HOME" to "Home Screen",
                                    "PLAYLISTS" to "Playlists"
                                ),
                                selectedOption = selectedLayoutCustomizerScreen,
                                onSelectOption = {
                                    HapticUtils.performClickHaptic(context)
                                    selectedLayoutCustomizerScreen = it
                                },
                                accentColor = Color(0xFFBB86FC)
                            )

                            // Interactive Miniature Preview Cards
                            when (selectedLayoutCustomizerScreen) {
                                "NOW_PLAYING" -> {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(NowPlayingLayout.entries, key = { it.id }, contentType = { "layout_preview" }) { layout ->
                                            val isSelected = nowPlayingLayout.equals(layout.id, ignoreCase = true)
                                            ScreenLayoutPreviewCard(
                                                title = layout.displayName,
                                                subtitle = layout.subtitle,
                                                isSelected = isSelected,
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    onSetNowPlayingLayout(layout.id)
                                                }
                                            ) {
                                                when (layout) {
                                                    NowPlayingLayout.MODERN_BENTO -> NowPlayingMiniatureModernBento()
                                                    NowPlayingLayout.VINYL_TURNTABLE -> NowPlayingMiniatureVinylTurntable()
                                                    NowPlayingLayout.MINIMAL_GLASS -> NowPlayingMiniatureMinimalGlass()
                                                    NowPlayingLayout.NEON_WAVEFORM -> NowPlayingMiniatureNeonWaveform()
                                                }
                                            }
                                        }
                                    }
                                }
                                "HOME" -> {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(HomeScreenLayout.entries, key = { it.id }, contentType = { "layout_preview" }) { layout ->
                                            val isSelected = homeScreenLayout.equals(layout.id, ignoreCase = true)
                                            ScreenLayoutPreviewCard(
                                                title = layout.displayName,
                                                subtitle = layout.subtitle,
                                                isSelected = isSelected,
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    onSetHomeScreenLayout(layout.id)
                                                }
                                            ) {
                                                when (layout) {
                                                    HomeScreenLayout.BENTO_DYNAMIC -> HomeScreenMiniatureBentoMosaic()
                                                    HomeScreenLayout.HERO_SPOTLIGHT -> HomeScreenMiniatureHeroSpotlight()
                                                    HomeScreenLayout.MINIMAL_FEED -> HomeScreenMiniatureMinimalFeed()
                                                    HomeScreenLayout.STUDIO_DASHBOARD -> HomeScreenMiniatureStudioDeck()
                                                }
                                            }
                                        }
                                    }
                                }
                                "PLAYLISTS" -> {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(PlaylistsLayout.entries, key = { it.id }, contentType = { "layout_preview" }) { layout ->
                                            val isSelected = playlistsLayout.equals(layout.id, ignoreCase = true)
                                            ScreenLayoutPreviewCard(
                                                title = layout.displayName,
                                                subtitle = layout.subtitle,
                                                isSelected = isSelected,
                                                onClick = {
                                                    HapticUtils.performClickHaptic(context)
                                                    onSetPlaylistsLayout(layout.id)
                                                }
                                            ) {
                                                when (layout) {
                                                    PlaylistsLayout.BENTO_GRID -> PlaylistsMiniatureBentoGrid()
                                                    PlaylistsLayout.MAGAZINE_HERO -> PlaylistsMiniatureMagazineHero()
                                                    PlaylistsLayout.COMPACT_LIST -> PlaylistsMiniatureCompactList()
                                                    PlaylistsLayout.GLOW_CARDS -> PlaylistsMiniatureGlowCards()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        SettingsDivider()

                        // 3. Display Mode Selection (Dark / Light / AMOLED / System)
                        val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
                        val currentTheme = com.example.ui.theme.AppUiTheme.fromId(uiTheme)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Display Mode", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                            SettingsSegmentedSelector(
                                options = listOf(
                                    "DARK" to "Dark",
                                    "LIGHT" to "Light",
                                    "AMOLED" to "AMOLED",
                                    "SYSTEM" to "System"
                                ),
                                selectedOption = themeMode.uppercase(),
                                onSelectOption = { newMode ->
                                    HapticUtils.performClickHaptic(context)
                                    if (!com.example.ui.theme.AppUiTheme.isThemeCompatibleWith(currentTheme, newMode, isSystemDark)) {
                                        val fallback = com.example.ui.theme.AppUiTheme.getDefaultThemeForMode(newMode, isSystemDark)
                                        onSetUiTheme(fallback.id)
                                    }
                                    onSetThemeMode(newMode)
                                },
                                accentColor = Color(0xFFBB86FC)
                            )
                        }

                        SettingsDivider()

                        // Display Scale (UI Size)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Display Scale", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                Text(
                                    text = when (displayScale.uppercase()) {
                                        "COMPACT" -> "Compact (0.92x)"
                                        "COMFORT" -> "Comfort (1.08x)"
                                        else -> "Standard (1.0x)"
                                    },
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary
                                )
                            }
                            Text(
                                "Controls overall UI component, font, and button sizing across all screens",
                                fontSize = 11.5.sp,
                                color = BentoTextSecondary
                            )
                            SettingsSegmentedSelector(
                                options = listOf(
                                    "COMPACT" to "Compact",
                                    "DEFAULT" to "Standard",
                                    "COMFORT" to "Comfort"
                                ),
                                selectedOption = displayScale.uppercase(),
                                onSelectOption = { newScale ->
                                    HapticUtils.performClickHaptic(context)
                                    onSetDisplayScale(newScale)
                                },
                                accentColor = Color(0xFFBB86FC)
                            )
                        }

                        SettingsDivider()

                        // 4. Accent Color Palette & Custom RGB Picker
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Accent Color Palette", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                    Text("Choose preset circle theme or fine-tune custom RGB color", fontSize = 11.5.sp, color = BentoTextSecondary)
                                }
                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        showColorPickerDialog = true
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (customAccentColor != 0) BentoPrimary.copy(alpha = 0.18f) else BentoCardPurple,
                                    border = BorderStroke(1.dp, if (customAccentColor != 0) BentoPrimary else BentoBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Palette,
                                            contentDescription = "Custom RGB",
                                            tint = if (customAccentColor != 0) Color(customAccentColor) else Color(0xFFBB86FC),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "RGB Picker",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary
                                        )
                                    }
                                }
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(PRESET_ACCENT_COLORS, key = { it.first }, contentType = { "accent_circle" }) { (name, color) ->
                                    val isSelected = accentColorPreset.equals(name, ignoreCase = true) && customAccentColor == 0 && !isDynamicArtworkTheme
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(
                                                width = if (isSelected) 3.dp else 1.dp,
                                                color = if (isSelected) Color.White else BentoBorder,
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                HapticUtils.performClickHaptic(context)
                                                onSetAccentColorPreset(name)
                                                onSetCustomAccentColor(0)
                                                onSetDynamicArtworkTheme(false)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color.Black,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        SettingsDivider()

                        // 5. Dynamic Artwork Theming
                        SettingsToggleRow(
                            title = "Dynamic Artwork Theming",
                            subtitle = "Adapt UI accents and background atmospheric glows to current song cover",
                            checked = isDynamicArtworkTheme,
                            onCheckedChange = onSetDynamicArtworkTheme,
                            icon = Icons.Default.AutoAwesome,
                            accentColor = Color(0xFFBB86FC)
                        )

                        SettingsDivider()

                        // 6. Prefer High-Res Online Artwork
                        SettingsToggleRow(
                            title = "Prefer High-Res Online Artwork",
                            subtitle = if (fetchOnlineArtworkHighRes) "High-res covers up to 1920px are fetched online & cached locally" else "Local embedded audio covers are prioritized for fast offline access",
                            checked = fetchOnlineArtworkHighRes,
                            onCheckedChange = onSetFetchOnlineArtworkHighRes,
                            icon = Icons.Default.Image,
                            accentColor = Color(0xFFBB86FC)
                        )

                        SettingsDivider()

                        // 7. Auto-Scroll Synced Lyrics
                        SettingsToggleRow(
                            title = "Auto-Scroll Synced Lyrics",
                            subtitle = "Keep the active singing lyric timestamp smoothly centered",
                            checked = lyricsAutoScroll,
                            onCheckedChange = onSetLyricsAutoScroll,
                            icon = Icons.Default.FormatQuote,
                            accentColor = Color(0xFFBB86FC)
                        )

                        SettingsDivider()

                        // 8. Waveform Visualizer Style Selection
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Waveform Visualizer Style", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "SLEEK_LINE" to "Sleek Line",
                                    "SNAKE" to "Snake Sine",
                                    "BARS" to "Symmetric",
                                    "DOTS" to "Dotted"
                                ).forEach { (style, label) ->
                                    val isSelected = when (style) {
                                        "SLEEK_LINE" -> waveformStyle.equals("SLEEK_LINE", true) || waveformStyle.equals("SLEEK", true) || waveformStyle.equals("MINIMAL", true) || waveformStyle.equals("LINE", true)
                                        "DOTS" -> waveformStyle.equals("DOTS", true) || waveformStyle.equals("CIRCLE", true) || waveformStyle.equals("DOTTED_CURVES", true)
                                        "BARS" -> waveformStyle.equals("BARS", true) || waveformStyle.equals("SYMMETRIC_CAPSULES", true) || waveformStyle.equals("CAPSULE", true) || waveformStyle.equals("SMOOTH_WAVE", true)
                                        "SNAKE" -> waveformStyle.equals("SNAKE", true) || waveformStyle.equals("CURVED", true)
                                        else -> waveformStyle.equals(style, ignoreCase = true)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) Color(0xFFBB86FC) else BentoCardPurple,
                                        border = BorderStroke(1.dp, if (isSelected) Color(0xFFBB86FC) else BentoBorder),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                HapticUtils.performClickHaptic(context)
                                                onSetWaveformStyle(style)
                                            }
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) BentoOnPrimary else BentoTextPrimary,
                                            modifier = Modifier.padding(vertical = 10.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        SettingsDivider()

                        // 9. High Refresh Rate Toggle (90/120Hz)
                        SettingsToggleRow(
                            title = "High Refresh Rate (90/120Hz)",
                            subtitle = "Enable ultra-smooth 120Hz display transitions & physics animations",
                            checked = highRefreshRate,
                            onCheckedChange = onSetHighRefreshRate,
                            icon = Icons.Default.Speed,
                            accentColor = Color(0xFFBB86FC)
                        )

                        SettingsDivider()

                        // 10. Mini Player Customization
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Mini Player Customization", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)

                            SettingsSegmentedSelector(
                                options = listOf(
                                    "FLOATING" to "Floating Pill",
                                    "BOTTOM_BAR" to "Bottom Bar"
                                ),
                                selectedOption = miniPlayerStyle.uppercase(),
                                onSelectOption = {
                                    HapticUtils.performClickHaptic(context)
                                    onSetMiniPlayerStyle(it)
                                },
                                accentColor = Color(0xFFBB86FC)
                            )

                            SettingsToggleRow(
                                title = "Show Previous Track Button",
                                subtitle = "Quick previous button on the persistent mini player",
                                checked = miniPlayerShowPrevious,
                                onCheckedChange = onSetMiniPlayerShowPrevious,
                                icon = Icons.Default.SkipPrevious,
                                accentColor = Color(0xFFBB86FC)
                            )

                            SettingsToggleRow(
                                title = "Show Next Track Button",
                                subtitle = "Quick next track button on the persistent mini player",
                                checked = miniPlayerShowNext,
                                onCheckedChange = onSetMiniPlayerShowNext,
                                icon = Icons.Default.SkipNext,
                                accentColor = Color(0xFFBB86FC)
                            )

                            SettingsToggleRow(
                                title = "Show Favorite Button",
                                subtitle = "Instant heart/favorite action on the persistent mini player",
                                checked = miniPlayerShowFavorite,
                                onCheckedChange = onSetMiniPlayerShowFavorite,
                                icon = Icons.Default.Favorite,
                                accentColor = Color(0xFFBB86FC)
                            )
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 3: HOME FEED & CATEGORIES
            // ==========================================
            if (showHomeFeed) {
                item(key = "settings_home_feed_header") {
                    SettingsSectionHeader(
                        title = "Home Screen Feed Architecture",
                        subtitle = "Customize section ordering, toggle categories & feed presets",
                        kicker = "HOME FEED & DISCOVERY",
                        icon = Icons.Default.DashboardCustomize,
                        accentColor = Color(0xFF7C4DFF)
                    )
                }

                item(key = "settings_home_feed_card") {
                    HomeScreenSectionsManagerCard(
                        currentSections = homeSections,
                        onUpdateSections = onSetHomeSections
                    )
                }
            }

            // ==========================================
            // SECTION 4: PLAYBACK CONTROLS & TIMERS
            // ==========================================
            if (showPlayback) {
                item(key = "settings_playback_header") {
                    SettingsSectionHeader(
                        title = "Playback Controls & Sleep Timer",
                        subtitle = "Smart sleep timers, song completion threshold, shake gestures & screen awake",
                        kicker = "PLAYBACK CONTROLS & TIMERS",
                        icon = Icons.Default.PlayCircle,
                        accentColor = Color(0xFFFF9100)
                    )
                }

                item(key = "settings_playback_card") {
                    SettingsCardContainer(accentGlow = Color(0xFFFF9100)) {
                        // Sleep Timer Hero Status Card
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (sleepTimerState.isActive) Color(0xFFFF9100).copy(alpha = 0.12f) else BentoCardPurple,
                            border = BorderStroke(1.dp, if (sleepTimerState.isActive) Color(0xFFFF9100).copy(alpha = 0.4f) else BentoBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(if (sleepTimerState.isActive) Color(0xFFFF9100).copy(alpha = 0.2f) else BentoCardPurple),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (sleepTimerState.isActive) Icons.Default.HourglassTop else Icons.Default.Bedtime,
                                            contentDescription = null,
                                            tint = if (sleepTimerState.isActive) Color(0xFFFF9100) else BentoTextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column {
                                        Text("Sleep Timer Status", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                        Text(
                                            text = if (sleepTimerState.isActive) {
                                                if (sleepTimerState.mode == "SONGS") {
                                                    if (sleepTimerState.isFinalSleepTrack) {
                                                        "Active: Final track (${sleepTimerState.elapsedSongs}/${sleepTimerState.totalSongs}) - stopping after track"
                                                    } else if (sleepTimerState.elapsedSongs > 0) {
                                                        "Active: Track ${sleepTimerState.elapsedSongs} of ${sleepTimerState.totalSongs} (${sleepTimerState.remainingSongs} remaining)"
                                                    } else {
                                                        "Active: ${sleepTimerState.remainingSongs} of ${sleepTimerState.totalSongs} track(s) remaining"
                                                    }
                                                } else {
                                                    if (sleepTimerState.isWaitingForCurrentTrackToEnd || sleepTimerState.remainingSeconds <= 0L) {
                                                        "Active: Finishing current track before stopping"
                                                    } else {
                                                        val mins = sleepTimerState.remainingSeconds / 60
                                                        val secs = sleepTimerState.remainingSeconds % 60
                                                        "Active: ${mins}m ${secs}s remaining${if (sleepTimerState.finishLastSong) " (finish track)" else ""}"
                                                    }
                                                }
                                            } else "Timer inactive • Select duration or track count below",
                                            fontSize = 11.5.sp,
                                            color = if (sleepTimerState.isActive) Color(0xFFFF9100) else BentoTextSecondary
                                        )
                                    }
                                }

                                if (sleepTimerState.isActive) {
                                    IconButton(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onCancelSleepTimer()
                                        }
                                    ) {
                                        Icon(Icons.Default.StopCircle, contentDescription = "Cancel Timer", tint = Color.Red, modifier = Modifier.size(28.dp))
                                    }
                                }
                            }
                        }

                        // Active timer extension buttons
                        if (sleepTimerState.isActive && sleepTimerState.mode == "TIME") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Extend Time:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSubtle)
                                listOf(5 to "+5m", 15 to "+15m", 30 to "+30m").forEach { (extraMins, label) ->
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onAddSleepTimerMinutes(extraMins)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFFF9100).copy(alpha = 0.2f),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFFF9100)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Finish Last Track Checkbox
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { finishLastSongSettings = !finishLastSongSettings }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = finishLastSongSettings,
                                onCheckedChange = { finishLastSongSettings = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFF9100))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Finish playing current track completely before stopping",
                                fontSize = 12.5.sp,
                                color = BentoTextPrimary
                            )
                        }

                        // Time-Based Quick Chips
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Time-Based Duration:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSubtle)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m").forEach { (mins, label) ->
                                    val isThisActive = sleepTimerState.isActive && sleepTimerState.mode == "TIME" && sleepTimerState.initialMinutes == mins
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onStartSleepTimer(mins, finishLastSongSettings)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isThisActive) Color(0xFFFF9100) else BentoCardPurple,
                                        border = BorderStroke(1.dp, if (isThisActive) Color(0xFFFF9100) else BentoBorder),
                                        modifier = Modifier.weight(1f).height(38.dp)
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = label,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isThisActive) Color.White else BentoTextPrimary
                                            )
                                        }
                                    }
                                }
                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        showCustomMinutesDialog = true
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = BentoCardPurple,
                                    border = BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.5f)),
                                    modifier = Modifier.weight(1.1f).height(38.dp)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "Custom",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFF9100)
                                        )
                                    }
                                }
                            }
                        }

                        // Track-Based Quick Chips
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Track-Based Counter (Song Count):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BentoTextSubtle)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(1 to "1 Trk", 2 to "2 Trks", 5 to "5 Trks", 10 to "10 Trks").forEach { (count, label) ->
                                    val isThisActive = sleepTimerState.isActive && sleepTimerState.mode == "SONGS" && sleepTimerState.totalSongs == count
                                    Surface(
                                        onClick = {
                                            HapticUtils.performClickHaptic(context)
                                            onStartSongCountTimer(count)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isThisActive) Color(0xFFFF9100) else BentoCardPurple,
                                        border = BorderStroke(1.dp, if (isThisActive) Color(0xFFFF9100) else BentoBorder),
                                        modifier = Modifier.weight(1f).height(38.dp)
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isThisActive) Color.White else BentoTextPrimary
                                            )
                                        }
                                    }
                                }
                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        showCustomTrackDialog = true
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = BentoCardPurple,
                                    border = BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.5f)),
                                    modifier = Modifier.weight(1.1f).height(38.dp)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "Custom",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFF9100)
                                        )
                                    }
                                }
                            }
                        }

                        SettingsDivider()

                        // Song Completion Criteria
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Track Completion Criteria", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                    Text(
                                        text = if (scrobbleThresholdType == "PERCENTAGE") "Count track as played after ${scrobbleThresholdValue}% completed" else "Count track as played after ${scrobbleThresholdValue} seconds",
                                        fontSize = 11.5.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFFF9100).copy(alpha = 0.18f),
                                    border = BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = if (scrobbleThresholdType == "PERCENTAGE") "${scrobbleThresholdValue}%" else "${scrobbleThresholdValue}s",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF9100),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            SettingsSegmentedSelector(
                                options = listOf(
                                    "SECONDS" to "By Duration (Sec)",
                                    "PERCENTAGE" to "By Percentage (%)"
                                ),
                                selectedOption = scrobbleThresholdType,
                                onSelectOption = {
                                    onSetScrobbleThresholdType(it)
                                    if (it == "PERCENTAGE" && scrobbleThresholdValue > 100) {
                                        onSetScrobbleThresholdValue(50)
                                    } else if (it == "SECONDS" && scrobbleThresholdValue < 10) {
                                        onSetScrobbleThresholdValue(30)
                                    }
                                },
                                accentColor = Color(0xFFFF9100)
                            )

                            if (scrobbleThresholdType == "PERCENTAGE") {
                                Slider(
                                    value = scrobbleThresholdValue.coerceIn(10, 100).toFloat(),
                                    onValueChange = { onSetScrobbleThresholdValue(it.toInt()) },
                                    valueRange = 10f..100f,
                                    steps = 8,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF9100),
                                        activeTrackColor = Color(0xFFFF9100),
                                        inactiveTrackColor = BentoCardPurple
                                    )
                                )
                            } else {
                                Slider(
                                    value = scrobbleThresholdValue.coerceIn(5, 180).toFloat(),
                                    onValueChange = { onSetScrobbleThresholdValue(it.toInt()) },
                                    valueRange = 5f..180f,
                                    steps = 34,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF9100),
                                        activeTrackColor = Color(0xFFFF9100),
                                        inactiveTrackColor = BentoCardPurple
                                    )
                                )
                            }
                        }

                        SettingsDivider()

                        // Shake to Skip
                        SettingsToggleRow(
                            title = "Shake to Skip Track",
                            subtitle = "Physically shake the device to advance to the next song in the queue",
                            checked = shakeToSkip,
                            onCheckedChange = onSetShakeToSkip,
                            icon = Icons.Default.Vibration,
                            accentColor = Color(0xFFFF9100)
                        )

                        SettingsDivider()

                        // Always Shuffle Mode
                        SettingsToggleRow(
                            title = "Always Shuffle Mode",
                            subtitle = "Automatically start playlist and album playback in shuffle order",
                            checked = alwaysShuffleMode,
                            onCheckedChange = onSetAlwaysShuffleMode,
                            icon = Icons.Default.Shuffle,
                            accentColor = Color(0xFFFF9100)
                        )

                        SettingsDivider()

                        // Autoplay on App Startup
                        SettingsToggleRow(
                            title = "Autoplay music on app startup",
                            subtitle = "Automatically begin music playback when the app is launched",
                            checked = autoPlayOnAppStartup,
                            onCheckedChange = onSetAutoPlayOnAppStartup,
                            icon = Icons.Default.PlayCircle,
                            accentColor = Color(0xFFFF9100)
                        )

                        SettingsDivider()

                        // Gesture-based Playback Control
                        SettingsToggleRow(
                            title = "Album Art Gestures",
                            subtitle = "Swipe horizontally on album art to skip tracks (left: previous, right: next) or tap to play/pause",
                            checked = gesturePlaybackEnabled,
                            onCheckedChange = onSetGesturePlaybackEnabled,
                            icon = Icons.Default.TouchApp,
                            accentColor = Color(0xFFFF9100)
                        )

                        SettingsDivider()

                        // Add to Queue Position
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Add to Queue Position", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                    Text(
                                        text = if (addToQueuePosition == "TOP_NEXT") "Top Next: insert immediately after currently playing track" else "Bottom: append to the end of Up-Next queue",
                                        fontSize = 11.5.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFFF9100).copy(alpha = 0.18f),
                                    border = BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = if (addToQueuePosition == "TOP_NEXT") "Top Next" else "Bottom",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF9100),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            SettingsSegmentedSelector(
                                options = listOf(
                                    "TOP_NEXT" to "Top Next",
                                    "BOTTOM" to "Bottom"
                                ),
                                selectedOption = addToQueuePosition,
                                onSelectOption = onSetAddToQueuePosition,
                                accentColor = Color(0xFFFF9100)
                            )
                        }

                        SettingsDivider()

                        // Add to Playlist Position in Custom Ordering
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Add to Playlist Position", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                    Text(
                                        text = if (playlistAddSongPosition == "TOP") "Top: insert at the beginning of custom playlist arrangement" else "Bottom: append to the end of custom playlist arrangement",
                                        fontSize = 11.5.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFFF9100).copy(alpha = 0.18f),
                                    border = BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = if (playlistAddSongPosition == "TOP") "Top" else "Bottom",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF9100),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            SettingsSegmentedSelector(
                                options = listOf(
                                    "BOTTOM" to "Bottom (End)",
                                    "TOP" to "Top (Beginning)"
                                ),
                                selectedOption = playlistAddSongPosition,
                                onSelectOption = onSetPlaylistAddSongPosition,
                                accentColor = Color(0xFFFF9100)
                            )
                        }

                        SettingsDivider()

                        // Automatically Play on Headphone Connection
                        SettingsToggleRow(
                            title = "Automatically Play on Headphone Connection",
                            subtitle = "Automatically start or resume music when wired or wireless headphones are connected",
                            checked = autoPlayOnHeadphoneConnection,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                        val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.BLUETOOTH_CONNECT
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        if (isGranted) {
                                            onSetAutoPlayOnHeadphoneConnection(true)
                                        } else {
                                            showHeadphonePermissionDialog = true
                                        }
                                    } else {
                                        onSetAutoPlayOnHeadphoneConnection(true)
                                    }
                                } else {
                                    onSetAutoPlayOnHeadphoneConnection(false)
                                }
                            },
                            icon = Icons.Default.Headphones,
                            accentColor = Color(0xFFFF9100)
                        )

                        SettingsDivider()

                        // Keep Screen Awake
                        SettingsToggleRow(
                            title = "Keep Screen Awake",
                            subtitle = "Prevent device display from sleeping while lyrics or player is active",
                            checked = keepScreenAwake,
                            onCheckedChange = onSetKeepScreenAwake,
                            icon = Icons.Default.WbSunny,
                            accentColor = Color(0xFFFF9100)
                        )
                    }
                }
            }

            // ==========================================
            // SECTION 5: CACHE & OFFLINE STORAGE
            // ==========================================
            if (showCache) {
                item(key = "settings_cache_header") {
                    LaunchedEffect(Unit) {
                        onRefreshCacheSizes()
                        onRefreshDatabaseStats()
                    }
                    SettingsSectionHeader(
                        title = "Offline Storage & Media Cache",
                        subtitle = "Fast local cache management for lyrics, high-res covers & disk usage",
                        kicker = "OFFLINE STORAGE & CACHE",
                        icon = Icons.Default.Storage,
                        accentColor = Color(0xFF00E676)
                    )
                }

                item(key = "settings_cache_card") {
                    SettingsCardContainer(accentGlow = Color(0xFF00E676)) {
                        // Total Cache Summary Banner
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BentoCardPurple,
                            border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Total Cache Occupied", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BentoTextSecondary)
                                    Text(totalCacheSize, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF00E676))
                                }
                                Button(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onClearAllCaches()
                                        android.widget.Toast.makeText(context, "All media caches cleared", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Clear All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        SettingsDivider()

                        // Cache Synced Lyrics
                        SettingsToggleRow(
                            title = "Cache Synced Lyrics",
                            subtitle = "Store time-synchronized lyrics locally for offline display ($lyricsCacheSize)",
                            checked = cacheLyricsEnabled,
                            onCheckedChange = onSetCacheLyricsEnabled,
                            icon = Icons.Default.FormatQuote,
                            accentColor = Color(0xFF00E676)
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onClearLyricsCache()
                                    android.widget.Toast.makeText(context, "Lyrics cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E676)),
                                border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear Lyrics ($lyricsCacheSize)", fontSize = 11.5.sp)
                            }
                        }

                        SettingsDivider()

                        // Cache Album Artwork
                        SettingsToggleRow(
                            title = "Cache Album Artwork",
                            subtitle = if (cacheAlbumArtEnabled) "High-res covers are stored on disk for instant offline loading ($albumArtCacheSize)" else "Artwork is fetched live from network on every song change",
                            checked = cacheAlbumArtEnabled,
                            onCheckedChange = onSetCacheAlbumArtEnabled,
                            icon = Icons.Default.Album,
                            accentColor = Color(0xFF00E676)
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onClearAlbumArtCache()
                                    android.widget.Toast.makeText(context, "Album artwork cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E676)),
                                border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear Album Art ($albumArtCacheSize)", fontSize = 11.5.sp)
                            }
                        }

                        SettingsDivider()

                        // Cache Song Artwork
                        SettingsToggleRow(
                            title = "Cache Song Artwork",
                            subtitle = if (cacheSongArtWorkEnabled) "Track-specific custom covers are persisted locally ($songArtCacheSize)" else "Individual song artwork is fetched dynamically from the network",
                            checked = cacheSongArtWorkEnabled,
                            onCheckedChange = onSetCacheSongArtWorkEnabled,
                            icon = Icons.Default.MusicNote,
                            accentColor = Color(0xFF00E676)
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onClearSongArtCache()
                                    android.widget.Toast.makeText(context, "Song artwork cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E676)),
                                border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear Song Art ($songArtCacheSize)", fontSize = 11.5.sp)
                            }
                        }
                    }
                }

                item(key = "settings_database_storage_card") {
                    var showImportDialog by remember { mutableStateOf(false) }
                    var importJsonText by remember { mutableStateOf("") }

                    SettingsCardContainer(accentGlow = Color(0xFF00E5FF)) {
                        // Header with icon and badge
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF00E5FF).copy(alpha = 0.18f))
                                        .border(BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Dns,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Room SQLite Database Engine",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                    Text(
                                        text = "Expanded storage capacity, WAL caching & optimization",
                                        fontSize = 11.5.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }
                        }

                        // DB Metrics Grid
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = BentoCardPurple,
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("Database Size", fontSize = 11.5.sp, color = BentoTextSecondary)
                                        Text(databaseStorageStats?.formattedMainDbSize ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                                    }
                                    Column {
                                        Text("WAL Journal", fontSize = 11.5.sp, color = BentoTextSecondary)
                                        Text(databaseStorageStats?.formattedWalSize ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                                    }
                                    Column {
                                        Text("Total Storage", fontSize = 11.5.sp, color = BentoTextSecondary)
                                        Text(databaseStorageStats?.formattedTotalDbSize ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                                    }
                                }
                                HorizontalDivider(color = BentoBorder)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("Indexed Songs", fontSize = 11.5.sp, color = BentoTextSecondary)
                                        Text("${databaseStorageStats?.songCount ?: 0}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                    }
                                    Column {
                                        Text("History Sessions", fontSize = 11.5.sp, color = BentoTextSecondary)
                                        Text("${databaseStorageStats?.historySessionCount ?: 0}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                    }
                                    Column {
                                        Text("Quota Remaining", fontSize = 11.5.sp, color = BentoTextSecondary)
                                        Text("${databaseStorageStats?.quotaRemaining ?: 50000}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF00E5FF))
                                    }
                                    Column {
                                        Text("Lyrics Cached", fontSize = 11.5.sp, color = BentoTextSecondary)
                                        Text("${databaseStorageStats?.cachedLyricsCount ?: 0}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Device Quota Usage Progress Bar
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Local Database Usage: ${databaseStorageStats?.historySessionCount ?: 0} / ${databaseStorageStats?.quotaLimit ?: 50000} records",
                                            fontSize = 11.sp,
                                            color = BentoTextSecondary
                                        )
                                        Text(
                                            "${((databaseStorageStats?.quotaUsagePercentage ?: 0f) * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if ((databaseStorageStats?.quotaUsagePercentage ?: 0f) > 0.9f) BentoAccentPinkText else Color(0xFF00E5FF)
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { databaseStorageStats?.quotaUsagePercentage ?: 0f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = if ((databaseStorageStats?.quotaUsagePercentage ?: 0f) > 0.9f) BentoAccentPinkText else Color(0xFF00E5FF),
                                        trackColor = BentoBorder
                                    )
                                }
                            }
                        }

                        SettingsDivider()

                        // Storage Capacity Tier Selector
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Database Capacity Tier", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                            Text("Configure playback history retention and caching limits for this device's local database", fontSize = 11.5.sp, color = BentoTextSecondary)
                        }

                        SettingsSegmentedSelector(
                            options = listOf(
                                "STANDARD" to "Standard (10K)",
                                "EXPANDED" to "Expanded (50K)",
                                "MAXIMUM" to "Maximum (100K)"
                            ),
                            selectedOption = databaseStorageTier,
                            onSelectOption = {
                                HapticUtils.performClickHaptic(context)
                                onSetDatabaseStorageTier(it)
                                android.widget.Toast.makeText(context, "Local database storage set to $it tier", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            accentColor = Color(0xFF00E5FF)
                        )

                        Text(
                            text = when (databaseStorageTier) {
                                "MAXIMUM" -> "Maximum: Retains up to 100,000 history sessions, 50,000 cached lyrics, and 90-day tracking data on this device. Best for massive libraries."
                                "STANDARD" -> "Standard: Retains up to 10,000 history sessions, 3,000 lyrics, and 30-day tracking data on this device. Minimal storage footprint."
                                else -> "Expanded: Retains up to 50,000 history sessions, 15,000 lyrics, and 60-day tracking data on this device. Recommended balance of deep stats and speed."
                            },
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary,
                            lineHeight = 16.sp
                        )

                        SettingsDivider()

                        // Actions: Optimize, Backup, Restore
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onOptimizeDatabase { _, message ->
                                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                },
                                enabled = !isOptimizingDatabase,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isOptimizingDatabase) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Optimizing...", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Optimize DB", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onExportDatabaseBackup { file ->
                                        if (file != null) {
                                            android.widget.Toast.makeText(context, "Backup saved to: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Failed to create backup", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export Backup", fontSize = 11.5.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    importJsonText = ""
                                    showImportDialog = true
                                }
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restore from Backup JSON", fontSize = 12.sp, color = Color(0xFF00E5FF))
                            }
                        }

                        if (showImportDialog) {
                            AlertDialog(
                                onDismissRequest = { showImportDialog = false },
                                title = { Text("Restore Library Backup", fontWeight = FontWeight.Bold) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "Paste the JSON content of your library backup to restore playlists, smart playlists, and favorites:",
                                            fontSize = 12.sp,
                                            color = BentoTextSecondary
                                        )
                                        OutlinedTextField(
                                            value = importJsonText,
                                            onValueChange = { importJsonText = it },
                                            label = { Text("Backup JSON") },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 120.dp, max = 220.dp),
                                            maxLines = 10
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showImportDialog = false
                                            if (importJsonText.isNotBlank()) {
                                                onImportDatabaseBackup(importJsonText) { _, msg ->
                                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                                    ) {
                                        Text("Restore")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showImportDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 6: LIBRARY & FOLDERS
            // ==========================================
            if (showLibrary) {
                item(key = "settings_library_header") {
                    SettingsSectionHeader(
                        title = "Library Scanning & Folders",
                        subtitle = "Audio indexing, short audio duration filter & custom music folders",
                        kicker = "LIBRARY SCANNING & FOLDERS",
                        icon = Icons.Default.Folder,
                        accentColor = Color(0xFF2979FF)
                    )
                }

                item(key = "settings_library_card") {
                    SettingsCardContainer(accentGlow = Color(0xFF2979FF)) {
                        // Authoritative Scan Progress & Status Banner (Shared State)
                        if (scanProgress.isScanning || scanProgress.status == com.example.scanner.ScanStatus.SCANNING) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = BentoPrimary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = BentoPrimary)
                                            Text("${scanProgress.displayTracksCount} tracks found", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BentoPrimary)
                                        }
                                        Text("Scanning in progress...", fontSize = 11.5.sp, color = BentoTextSecondary)
                                    }
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = BentoPrimary)
                                }
                            }
                            SettingsDivider()
                        } else if (scanProgress.status == com.example.scanner.ScanStatus.COMPLETED && scanProgress.isFinished) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF00E676).copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${scanProgress.displayTracksCount} tracks found", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                                    Text("Scan completed", fontSize = 11.5.sp, color = BentoTextSecondary)
                                }
                            }
                            SettingsDivider()
                        } else if (scanProgress.status == com.example.scanner.ScanStatus.ERROR) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFFF5252).copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(scanProgress.errorMessage ?: "Scan error", fontSize = 12.5.sp, color = Color(0xFFFF5252))
                                    Text("Error", fontSize = 11.5.sp, color = BentoTextSecondary)
                                }
                            }
                            SettingsDivider()
                        }

                        // Ignore Short Audio Tracks Filter with quick presets (15s, 30s, 45s, 1m, 2m) and slider
                        AudioDurationFilterSetting(
                            ignoreAudioDuration = ignoreAudioDuration,
                            onSetIgnoreAudioDuration = onSetIgnoreAudioDuration
                        )

                        SettingsDivider()

                        val isCurrentlyScanning = scanProgress.isScanning || scanProgress.status == com.example.scanner.ScanStatus.SCANNING

                        // Quick Scan & Clean
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Quick Scan & Clean", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                Text("Quickly prune missing audio files and index newly downloaded tracks without disturbing edits", fontSize = 11.5.sp, color = BentoTextSecondary)
                            }
                            Button(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onQuickScanAndCleanLibrary()
                                    android.widget.Toast.makeText(context, "Quick scan & clean started", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                enabled = !isCurrentlyScanning,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF), contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Quick Scan", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        SettingsDivider()

                        // Custom Music Folders
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Custom Music Directories", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                Text("Select explicit directories to include or exclude from indexing", fontSize = 11.5.sp, color = BentoTextSecondary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = {
                                        isPickingForExclusion = false
                                        folderPickerLauncher.launch(null)
                                    },
                                    modifier = Modifier.clip(CircleShape).background(BentoCardPurple)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add Folder", tint = Color(0xFF2979FF))
                                }
                                IconButton(
                                    onClick = {
                                        isPickingForExclusion = true
                                        folderPickerLauncher.launch(null)
                                    },
                                    modifier = Modifier.clip(CircleShape).background(BentoCardPurple)
                                ) {
                                    Icon(Icons.Default.Block, contentDescription = "Exclude Folder", tint = Color.Red)
                                }
                            }
                        }

                        if (folders.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                folders.forEach { folder ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(BentoCardPurple.copy(alpha = 0.6f))
                                            .border(BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(
                                                imageVector = if (folder.isExcluded) Icons.Default.Block else Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = if (folder.isExcluded) Color.Red else Color(0xFF2979FF),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(folder.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                                Text(folder.path, fontSize = 11.sp, color = BentoTextSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        IconButton(
                                            onClick = { onRemoveFolder(folder.path) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = BentoTextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 7: OTHER & PRIVACY
            // ==========================================
            if (showOther) {
                item(key = "settings_other_header") {
                    SettingsSectionHeader(
                        title = "Search History & Diagnostics",
                        subtitle = "Query privacy controls, log inspection & device crash reports",
                        kicker = "PRIVACY & SYSTEM HEALTH",
                        icon = Icons.Default.Security,
                        accentColor = Color(0xFFFF5252)
                    )
                }

                item(key = "settings_other_card") {
                    SettingsCardContainer(accentGlow = Color(0xFFFF5252)) {
                        // Recent Search History
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Recent Search Queries", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                Text("${recentSearches.size} search queries recorded locally on device", fontSize = 11.5.sp, color = BentoTextSecondary)
                            }
                            if (recentSearches.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onClearRecentSearches()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                    border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f))
                                ) {
                                    Text("Clear All", fontSize = 11.5.sp)
                                }
                            }
                        }

                        SettingsDivider()

                        // Diagnostics & Crash Logs
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val allCrashReports = remember(diagnosticsRefreshTrigger) {
                                try { com.example.util.CrashReporter.getAllCrashReports(context) } catch (_: Exception) { emptyList() }
                            }
                            val crashReportCount = allCrashReports.size

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Diagnostics & Error Reports", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                                    Text(
                                        if (crashReportCount > 0) "$crashReportCount error report(s) available on device" else "No errors recorded. Engine operating normally.",
                                        fontSize = 11.5.sp,
                                        color = if (crashReportCount > 0) Color(0xFFFFB74D) else BentoTextSecondary
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val txtFile = com.example.util.CrashReporter.exportReportsAsTxt(context)
                                            com.example.util.CrashReporter.shareExportedReport(context, txtFile, "text/plain")
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export .TXT", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val jsonFile = com.example.util.CrashReporter.exportReportsAsJson(context)
                                            com.example.util.CrashReporter.shareExportedReport(context, jsonFile, "application/json")
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export .JSON", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                }

                                if (crashReportCount > 0) {
                                    OutlinedButton(
                                        onClick = {
                                            com.example.util.CrashReporter.clearCrashReports(context)
                                            diagnosticsRefreshTrigger++
                                            android.widget.Toast.makeText(context, "Diagnostics logs cleared", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoError),
                                        border = BorderStroke(1.dp, BentoError.copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Clear", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 8: CLOUD & CROSS-DEVICE SYNC
            // ==========================================
            if (showCloudSync) {
                item(key = "settings_cloud_sync_header") {
                    SettingsSectionHeader(
                        title = "Cloud & Cross-Device Sync",
                        subtitle = "Real-time Google Cloud backup for favorites, playlists, statistics and settings",
                        kicker = "CLOUD & DATA BACKUP",
                        icon = Icons.Default.CloudSync,
                        accentColor = Color(0xFF00E5FF)
                    )
                }

                item(key = "settings_cloud_sync_card") {
                    SettingsCardContainer(accentGlow = Color(0xFF00E5FF)) {
                        // User Account Summary Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val avatarPhoto = customAvatarUri ?: userAccount?.photoUrl
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF00E5FF).copy(alpha = 0.16f),
                                border = BorderStroke(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.45f)),
                                modifier = Modifier.size(46.dp)
                            ) {
                                if (!avatarPhoto.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = avatarPhoto,
                                        contentDescription = "Avatar",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else if (userAccount != null) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = (effectiveDisplayName.firstOrNull() ?: 'U').uppercase(),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00E5FF)
                                        )
                                    }
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = "Avatar",
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (userAccount != null) effectiveDisplayName else "Local Guest Profile",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (userAccount != null) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF00E676).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = if (userAccount != null) userAccount.friendlyProviderName else "Offline Only",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (userAccount != null) Color(0xFF00E5FF) else Color(0xFF00E676),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = userAccount?.email ?: "Sign in to backup your favorites, playlists & stats across devices",
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        SettingsDivider()

                        // Cloud Synchronization & Backup Section
                        com.example.ui.components.CloudSyncBackupSection(
                            automaticBackupEnabled = automaticBackupEnabled,
                            onToggleAutomaticBackup = onToggleAutomaticBackup,
                            backupFrequency = backupFrequency,
                            onSelectFrequency = onSelectFrequency,
                            lastSyncedTimestamp = lastSyncedTimestamp,
                            lastBackupTimestamp = lastBackupTimestamp,
                            syncStatus = syncStatus,
                            backupStatus = backupStatus,
                            onSyncNow = onTriggerSync,
                            onBackupNow = onTriggerBackup,
                            isUserSignedIn = userAccount != null,
                            onOpenAuth = onOpenAuth
                        )

                        SettingsDivider()

                        // What gets synced summary tiles
                        Text(
                            text = "AUTOMATICALLY SYNCHRONIZED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSubtle,
                            letterSpacing = 1.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Triple(Icons.Default.Favorite, "Favorites", "Hearted songs"),
                                Triple(Icons.AutoMirrored.Filled.QueueMusic, "Playlists", "Custom playlists"),
                                Triple(Icons.Default.Insights, "Statistics", "Listening history"),
                                Triple(Icons.Default.Tune, "Preferences", "Theme & audio EQ")
                            ).forEach { (icon, title, desc) ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = BentoCardPurple,
                                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary,
                                            textAlign = TextAlign.Center
                                        )
                                        Text(
                                            text = desc,
                                            fontSize = 9.sp,
                                            color = BentoTextSecondary,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        SettingsDivider()

                        // Manage Account / Auth Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (userAccount != null) {
                                OutlinedButton(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onOpenAccount()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Account Details", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onSignOut()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, BentoError.copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoError),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Sign Out", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onOpenAuth()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF00E5FF),
                                        contentColor = Color.Black
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Sign In or Register with Cloud", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // No Search Results State
            if (isSearching && !anyMatches) {
                item(key = "settings_empty_search") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = BentoTextSubtle, modifier = Modifier.size(36.dp))
                            Text(
                                text = "No settings matching \"$searchQuery\"",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Try searching for terms like: audio, crossfade, lyrics, awake, sleep, equalizer, theme, cache...",
                                fontSize = 12.sp,
                                color = BentoTextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = { searchQuery = "" },
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                            ) {
                                Text("Clear Search", color = BentoOnPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ThemePreviewCard(
    theme: AppUiTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBorderColor = if (isSelected) {
        theme.previewPrimary
    } else if (theme.isDark) {
        BentoBorder.copy(alpha = 0.55f)
    } else {
        Color(0xFFCBD5E1)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = theme.previewBg,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = cardBorderColor
        ),
        modifier = modifier
            .width(168.dp)
            .height(160.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top: Theme Accent Dots and Active checkmark
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(theme.previewPrimary))
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(theme.previewAccent))
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(theme.previewSurface))
                    }
                    if (isSelected) {
                        Surface(
                            shape = CircleShape,
                            color = theme.previewPrimary,
                            modifier = Modifier.size(20.dp)
                        ) {
                            val lum = 0.299f * theme.previewPrimary.red + 0.587f * theme.previewPrimary.green + 0.114f * theme.previewPrimary.blue
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Active",
                                tint = if (lum > 0.55f) Color.Black else Color.White,
                                modifier = Modifier.padding(3.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(20.dp))
                    }
                }

                // Middle: Mockup UI Card
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = theme.previewSurface,
                    border = BorderStroke(0.75.dp, theme.previewPrimary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(theme.previewAccent)
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(if (theme.isDark) Color.White.copy(alpha = 0.9f) else Color(0xFF1E293B))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.55f)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(theme.previewPrimary)
                            )
                        }
                    }
                }

                // Bottom: Title & Subtitle with guaranteed spacing and overflow protection
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = theme.displayName,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (theme.isDark) Color.White else Color(0xFF0F172A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = theme.subtitle,
                        fontSize = 10.sp,
                        color = if (theme.isDark) Color(0xFFA1A1AA) else Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioDurationFilterSetting(
    ignoreAudioDuration: Int,
    onSetIgnoreAudioDuration: (Int) -> Unit
) {
    val presets = listOf(
        0 to "All Audio",
        15 to "15s",
        30 to "30s",
        45 to "45s",
        60 to "1m",
        120 to "2m"
    )

    var currentSliderValue by remember(ignoreAudioDuration) {
        mutableFloatStateOf(ignoreAudioDuration.toFloat().coerceIn(0f, 180f))
    }
    var isDragging by remember { mutableStateOf(false) }

    val displayDuration = (if (isDragging) currentSliderValue.roundToInt() else ignoreAudioDuration).coerceIn(0, 180)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2979FF).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = null,
                        tint = Color(0xFF2979FF),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Filter Short Audio Clips",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Ignore audio files shorter than the selected duration during library scan",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (displayDuration > 0) Color(0xFF2979FF).copy(alpha = 0.2f) else BentoCardDarkPurple)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = when {
                        displayDuration == 0 -> "All Files"
                        displayDuration < 60 -> "< ${displayDuration}s"
                        displayDuration % 60 == 0 -> "< ${displayDuration / 60}m"
                        else -> "< ${displayDuration / 60}m ${displayDuration % 60}s"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (displayDuration > 0) Color(0xFF2979FF) else BentoTextSecondary
                )
            }
        }

        // Quick Preset Chips (0s, 15s, 30s, 45s, 1m, 2m)
        val filterContext = LocalContext.current
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(presets, key = { it.first }, contentType = { "duration_chip" }) { (sec, label) ->
                val isSelected = displayDuration == sec
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) Color(0xFF2979FF) else BentoCardPurple,
                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF2979FF) else BentoBorder.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable {
                        HapticUtils.performClickHaptic(filterContext)
                        isDragging = false
                        currentSliderValue = sec.toFloat()
                        onSetIgnoreAudioDuration(sec)
                    }
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else BentoTextSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Continuous Smooth Slider for custom durations up to 180s (3m)
        Slider(
            value = currentSliderValue,
            onValueChange = { newVal ->
                isDragging = true
                currentSliderValue = newVal
            },
            onValueChangeFinished = {
                isDragging = false
                val finalSec = currentSliderValue.roundToInt().coerceIn(0, 180)
                onSetIgnoreAudioDuration(finalSec)
            },
            valueRange = 0f..180f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF2979FF),
                activeTrackColor = Color(0xFF2979FF),
                inactiveTrackColor = BentoCardBorder
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
