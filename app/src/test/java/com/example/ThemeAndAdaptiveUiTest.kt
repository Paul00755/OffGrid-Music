package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.data.sync.CloudUserSettings
import com.example.service.SleepTimerState
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LocalGlassThemeConfig
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.isExplicitDarkColor
import com.example.ui.theme.toAdaptiveThemeAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeAndAdaptiveUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cloudUserSettingsRoundTripsUseDarkColors() {
        val enabled = CloudUserSettings(useDarkColors = true, accentColorPreset = "DARK_RED")
        val map = enabled.toMap()
        assertEquals(true, map["useDarkColors"])

        val restored = CloudUserSettings.fromMap(map)
        assertTrue(restored.useDarkColors)
        assertEquals("DARK_RED", restored.accentColorPreset)

        val defaultRestored = CloudUserSettings.fromMap(emptyMap())
        assertFalse(defaultRestored.useDarkColors)
    }

    @Test
    fun darkColorHelpersPreserveDarkRedAndDarkPurpleWithoutWashingOut() {
        val darkRed = Color(0xFF8B1A1A)
        val darkPurple = Color(0xFF4A148C)
        val brightCyan = Color(0xFF80DEEA)

        assertTrue("Dark red should be detected as explicit dark color", darkRed.isExplicitDarkColor())
        assertTrue("Dark purple should be detected as explicit dark color", darkPurple.isExplicitDarkColor())
        assertFalse("Bright cyan should not be detected as explicit dark color", brightCyan.isExplicitDarkColor())

        val preservedRed = darkRed.toAdaptiveThemeAccent(useDarkColors = false, preserveExactDark = true)
        val preservedPurple = darkPurple.toAdaptiveThemeAccent(useDarkColors = false, preserveExactDark = true)
        assertTrue(
            "Preserved dark red should stay deep dark instead of washing out to pastel (lum=${preservedRed.luminance()})",
            preservedRed.luminance() < 0.25f
        )
        assertTrue(
            "Preserved dark purple should stay deep dark instead of washing out to pastel (lum=${preservedPurple.luminance()})",
            preservedPurple.luminance() < 0.25f
        )

        val pastelCyan = brightCyan.toAdaptiveThemeAccent(useDarkColors = false, preserveExactDark = false)
        val richDarkCyan = brightCyan.toAdaptiveThemeAccent(useDarkColors = true, preserveExactDark = false)
        assertTrue(
            "useDarkColors=true should produce richer darker accent than palette lite pastel",
            richDarkCyan.luminance() < pastelCyan.luminance()
        )
    }

    @Test
    fun myApplicationThemePropagatesDarkColorAndDynamicFlags() {
        var capturedUseDarkColors = false
        var capturedIsCustomDark = false
        var capturedDynamic = true
        var capturedTint = Color.White

        composeTestRule.setContent {
            MyApplicationTheme(
                themeMode = "AMOLED",
                accentColorPreset = "DARK_RED",
                useDarkColors = true,
                isDynamicArtworkEnabled = false
            ) {
                val config = LocalGlassThemeConfig.current
                capturedUseDarkColors = config.useDarkColors
                capturedIsCustomDark = config.isCustomDarkAccent
                capturedDynamic = config.isDynamicArtworkEnabled
                capturedTint = config.tintColor
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(capturedUseDarkColors)
        assertTrue(capturedIsCustomDark)
        assertFalse(capturedDynamic)
        assertTrue("DARK_RED preset should remain dark red in glass tint", capturedTint.luminance() < 0.30f)
    }

    @Test
    fun settingsUiAndThemeDisplaysAndTogglesUseDarkColors() {
        var useDarkColors by mutableStateOf(false)

        composeTestRule.setContent {
            MyApplicationTheme(useDarkColors = useDarkColors) {
                SettingsScreen(
                    folders = emptyList(),
                    sleepTimerState = SleepTimerState(),
                    waveformStyle = "SNAKE",
                    themeMode = "DARK",
                    uiTheme = "MIDNIGHT_OBSIDIAN",
                    homeScreenLayout = "HERO_SPOTLIGHT",
                    nowPlayingLayout = "MINIMAL_GLASS",
                    playlistsLayout = "BENTO_GRID",
                    isDynamicArtworkTheme = false,
                    useDarkColors = useDarkColors,
                    onSetUseDarkColors = { useDarkColors = it },
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
                    customAccentColor = 0xFF8B1A1A.toInt(),
                    accentColorPreset = "DARK_RED",
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
                    homeSections = listOf("continue_listening", "quick_actions", "listening_stats"),
                    initialTargetSection = "UI"
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_lazy_column")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("settings_use_dark_colors_toggle"))
        composeTestRule.onNodeWithText("Use dark colors").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Turn off to keep using palette lite colors").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_use_dark_colors_toggle").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue("Clicking 'Use dark colors' toggle should enable useDarkColors", useDarkColors)
    }

    @Test
    fun settingsHubOmitsBrowseAllAndPlacesVolumeAutomationInPlayback() {
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    folders = emptyList(),
                    sleepTimerState = SleepTimerState(),
                    waveformStyle = "SNAKE",
                    themeMode = "DARK",
                    uiTheme = "MIDNIGHT_OBSIDIAN",
                    homeScreenLayout = "HERO_SPOTLIGHT",
                    nowPlayingLayout = "MINIMAL_GLASS",
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
                    customAccentColor = 0xFF8B1A1A.toInt(),
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
                    homeSections = listOf("continue_listening", "quick_actions", "listening_stats"),
                    initialTargetSection = null
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Browse All Settings Continuously").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings_hub_view_all_footer").assertDoesNotExist()
    }

    @Test
    fun playbackAutomationLivesInPlaybackNotAudio() {
        var section by mutableStateOf("PLAYBACK")

        composeTestRule.setContent {
            MyApplicationTheme {
                androidx.compose.runtime.key(section) {
                    SettingsScreen(
                        folders = emptyList(),
                        sleepTimerState = SleepTimerState(),
                        waveformStyle = "SNAKE",
                        themeMode = "DARK",
                        uiTheme = "MIDNIGHT_OBSIDIAN",
                        homeScreenLayout = "HERO_SPOTLIGHT",
                        nowPlayingLayout = "MINIMAL_GLASS",
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
                        customAccentColor = 0xFF8B1A1A.toInt(),
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
                        homeSections = listOf("continue_listening", "quick_actions", "listening_stats"),
                        initialTargetSection = section
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_lazy_column")
            .performScrollToNode(androidx.compose.ui.test.hasText("Pause on Zero Volume"))
        composeTestRule.onNodeWithText("Pause on Zero Volume").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto Resume on Volume Increase").assertIsDisplayed()

        section = "AUDIO"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Pause on Zero Volume").assertDoesNotExist()
        composeTestRule.onNodeWithText("Auto Resume on Volume Increase").assertDoesNotExist()
    }

    @Test
    fun homeFeedSettingsSupportsCustomNamedPreset() {
        var savedName: String? = null
        var savedSections: List<String>? = null
        val presets = listOf(
            com.example.data.local.CustomHomeFeedPreset(
                name = "Workout Flow",
                sections = listOf("continue_listening", "quick_actions", "most_played")
            )
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    folders = emptyList(),
                    sleepTimerState = SleepTimerState(),
                    waveformStyle = "SNAKE",
                    themeMode = "DARK",
                    uiTheme = "MIDNIGHT_OBSIDIAN",
                    homeScreenLayout = "HERO_SPOTLIGHT",
                    nowPlayingLayout = "MINIMAL_GLASS",
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
                    customAccentColor = 0xFF8B1A1A.toInt(),
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
                    homeSections = listOf("continue_listening", "quick_actions", "listening_stats"),
                    customHomeFeedPresets = presets,
                    onSaveCustomHomeFeedPreset = { name, secs ->
                        savedName = name
                        savedSections = secs
                    },
                    initialTargetSection = "HOME"
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Workout Flow").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_feed_save_custom_preset_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_feed_custom_preset_name_input")
            .performTextInput("Night Focus")
        composeTestRule.onNodeWithTag("home_feed_save_preset_confirm_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Night Focus", savedName)
        assertEquals(listOf("continue_listening", "quick_actions", "listening_stats"), savedSections)
    }
}
