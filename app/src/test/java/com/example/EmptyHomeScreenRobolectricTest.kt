package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.service.PlaybackState
import com.example.service.SleepTimerState
import com.example.scanner.ScanProgress
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EmptyHomeScreenRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testEmptyHomeScreenRendersWithoutCrash() {
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    allSongs = emptyList(),
                    favoriteSongs = emptyList(),
                    recentlyPlayed = emptyList(),
                    recentlyAdded = emptyList(),
                    allPlaylists = emptyList(),
                    playbackState = PlaybackState(),
                    scanProgress = ScanProgress(),
                    sleepTimerState = SleepTimerState(),
                    onSongClick = {},
                    onPlaySongQueue = { _, _ -> },
                    onTogglePlayPause = {},
                    onQuickShuffle = {},
                    onScanClick = {},
                    onFavoriteToggle = {},
                    onOpenNowPlaying = {},
                    onOpenSettings = {},
                    onOpenFavorites = {},
                    onOpenLibrary = {},
                    onOpenPlaylists = {},
                    onOpenSearch = {},
                    onSelectPlaylist = {},
                    onPlayNext = {},
                    onAddToQueue = {},
                    onAddToPlaylist = {},
                    onDeleteSong = {},
                    onEditTags = {},
                    getDurationPlayedBetween = { _, _ -> flowOf(0L) },
                    getTrackCountBetween = { _, _ -> flowOf(0) },
                    getUniqueTrackCountBetween = { _, _ -> flowOf(0) },
                    getMostPlayedSongsSince = { _, _ -> flowOf(emptyList()) },
                    getTopArtistsSince = { _, _ -> flowOf(emptyList()) },
                    getTopAlbumsSince = { _, _ -> flowOf(emptyList()) }
                )
            }
        }

        // Verify that empty state elements are present and rendered cleanly
        composeTestRule.onNodeWithText("OffGrid Music", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Quick Actions", useUnmergedTree = true).assertExists()
    }

    @Test
    fun testScanningHomeScreenRendersWithoutCrash() {
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    allSongs = emptyList(),
                    favoriteSongs = emptyList(),
                    recentlyPlayed = emptyList(),
                    recentlyAdded = emptyList(),
                    allPlaylists = emptyList(),
                    playbackState = PlaybackState(),
                    scanProgress = ScanProgress(isScanning = true, processedCount = 15, totalDiscovered = 50, currentFolder = "/storage/Music"),
                    sleepTimerState = SleepTimerState(),
                    onSongClick = {},
                    onPlaySongQueue = { _, _ -> },
                    onTogglePlayPause = {},
                    onQuickShuffle = {},
                    onScanClick = {},
                    onFavoriteToggle = {},
                    onOpenNowPlaying = {},
                    onOpenSettings = {},
                    onOpenFavorites = {},
                    onOpenLibrary = {},
                    onOpenPlaylists = {},
                    onOpenSearch = {},
                    onSelectPlaylist = {},
                    onPlayNext = {},
                    onAddToQueue = {},
                    onAddToPlaylist = {},
                    onDeleteSong = {},
                    onEditTags = {},
                    getDurationPlayedBetween = { _, _ -> flowOf(0L) },
                    getTrackCountBetween = { _, _ -> flowOf(0) },
                    getUniqueTrackCountBetween = { _, _ -> flowOf(0) },
                    getMostPlayedSongsSince = { _, _ -> flowOf(emptyList()) },
                    getTopArtistsSince = { _, _ -> flowOf(emptyList()) },
                    getTopAlbumsSince = { _, _ -> flowOf(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithText("Scanning Music Library...").assertIsDisplayed()
    }
}
