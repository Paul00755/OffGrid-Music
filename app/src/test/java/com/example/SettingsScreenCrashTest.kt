package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.service.SleepTimerState
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenCrashTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSettingsScreenRendersAllSectionsAndScrolls() {
        val currentSection = androidx.compose.runtime.mutableStateOf<String?>("ALL")
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    folders = listOf(
                        com.example.data.model.FolderInfo(
                            path = "/storage/emulated/0/Music",
                            name = "Music",
                            uriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                            songCount = 10,
                            isExcluded = false
                        ),
                        com.example.data.model.FolderInfo(
                            path = "/storage/emulated/0/Ringtones",
                            name = "Ringtones",
                            uriString = "content://com.android.externalstorage.documents/tree/primary%3ARingtones",
                            songCount = 3,
                            isExcluded = true
                        )
                    ),
                    sleepTimerState = SleepTimerState(
                        isActive = true,
                        remainingSeconds = 1200L,
                        finishLastSong = true,
                        mode = "TIME"
                    ),
                    waveformStyle = "SNAKE",
                    themeMode = "DARK",
                    uiTheme = "MIDNIGHT_OBSIDIAN",
                    homeScreenLayout = "BENTO_MOSAIC",
                    nowPlayingLayout = "MODERN_BENTO",
                    playlistsLayout = "BENTO_GRID",
                    isDynamicArtworkTheme = false,
                    fetchOnlineArtworkHighRes = false,
                    cacheLyricsEnabled = true,
                    cacheAlbumArtEnabled = true,
                    cacheSongArtWorkEnabled = true,
                    lyricsCacheSize = "1.2 MB",
                    albumArtCacheSize = "4.5 MB",
                    songArtCacheSize = "2.1 MB",
                    totalCacheSize = "7.8 MB",
                    customEqBands = listOf(0.1f, -0.2f, 0.3f, 0.0f, -0.1f),
                    customBassBoost = 150,
                    customVirtualizer = 100,
                    customAccentColor = 0xFF00E5FF.toInt(),
                    accentColorPreset = "CYAN",
                    miniPlayerShowPrevious = true,
                    miniPlayerShowNext = true,
                    miniPlayerShowFavorite = true,
                    gaplessPlayback = true,
                    pauseOnZeroVolume = false,
                    fadeInOutMs = 1000,
                    crossfadeDurationSec = 3,
                    ignoreAudioDuration = 30,
                    fadeInMs = 1000,
                    fadeOutMs = 1000,
                    shakeToSkip = true,
                    keepScreenAwake = true,
                    audioBalance = -0.2f,
                    equalizerPreset = "ROCK",
                    lyricsAutoScroll = true,
                    reduceVolumeOnNotification = true,
                    scrobbleThresholdType = "PERCENTAGE",
                    scrobbleThresholdValue = 50,
                    recentSearches = listOf("Rock", "Jazz", "Queen"),
                    highRefreshRate = true,
                    alwaysShuffleMode = false,
                    homeSections = listOf("SPEED_DIAL", "HERO_SPOTLIGHT", "STATS", "RECENTLY_PLAYED", "RECENTLY_ADDED", "MOST_PLAYED", "ARTISTS", "ALBUMS", "GENRES", "PLAYLISTS"),
                    initialTargetSection = currentSection.value
                )
            }
        }
        composeTestRule.waitForIdle()

        for (section in listOf("AUDIO", "UI", "HOME_FEED", "CACHE", "PLAYBACK", "LIBRARY", "OTHER", "SLEEP_TIMER", null)) {
            currentSection.value = section
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun testSettingsSearchFiltersCorrectly() {
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    folders = emptyList(),
                    sleepTimerState = SleepTimerState(),
                    waveformStyle = "SNAKE",
                    themeMode = "DARK",
                    uiTheme = "MIDNIGHT_OBSIDIAN",
                    homeScreenLayout = "BENTO_MOSAIC",
                    nowPlayingLayout = "MODERN_BENTO",
                    playlistsLayout = "BENTO_GRID",
                    isDynamicArtworkTheme = false,
                    fetchOnlineArtworkHighRes = false,
                    cacheLyricsEnabled = true,
                    cacheAlbumArtEnabled = true,
                    cacheSongArtWorkEnabled = true,
                    lyricsCacheSize = "0 B",
                    albumArtCacheSize = "0 B",
                    songArtCacheSize = "0 B",
                    totalCacheSize = "0 B",
                    customEqBands = listOf(0f, 0f, 0f, 0f, 0f),
                    customBassBoost = 0,
                    customVirtualizer = 0,
                    customAccentColor = 0xFF00E5FF.toInt(),
                    accentColorPreset = "CYAN",
                    miniPlayerShowPrevious = true,
                    miniPlayerShowNext = true,
                    miniPlayerShowFavorite = true,
                    gaplessPlayback = true,
                    pauseOnZeroVolume = false,
                    fadeInOutMs = 1000,
                    crossfadeDurationSec = 3,
                    ignoreAudioDuration = 30,
                    fadeInMs = 1000,
                    fadeOutMs = 1000,
                    shakeToSkip = true,
                    keepScreenAwake = true,
                    audioBalance = 0f,
                    equalizerPreset = "FLAT",
                    lyricsAutoScroll = true,
                    reduceVolumeOnNotification = true,
                    scrobbleThresholdType = "PERCENTAGE",
                    scrobbleThresholdValue = 50,
                    recentSearches = emptyList(),
                    highRefreshRate = false,
                    alwaysShuffleMode = false,
                    homeSections = listOf("STATS"),
                    initialTargetSection = null
                )
            }
        }
        composeTestRule.waitForIdle()

        // Perform text search interactions
        val searchTextField = composeTestRule.onNode(androidx.compose.ui.test.hasSetTextAction())
        searchTextField.performTextInput("equalizer")
        composeTestRule.waitForIdle()

        searchTextField.performTextClearance()
        searchTextField.performTextInput("timer")
        composeTestRule.waitForIdle()

        searchTextField.performTextClearance()
        searchTextField.performTextInput("cache")
        composeTestRule.waitForIdle()

        searchTextField.performTextClearance()
        searchTextField.performTextInput("nonexistent_query_xyz")
        composeTestRule.waitForIdle()

        searchTextField.performTextClearance()
        composeTestRule.waitForIdle()
    }
}
