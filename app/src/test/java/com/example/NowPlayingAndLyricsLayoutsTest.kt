package com.example

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.data.sync.CloudUserSettings
import com.example.service.PlaybackState
import com.example.service.SleepTimerState
import com.example.ui.screens.NowPlayingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.layouts.AlternateLyricsPanel
import com.example.ui.theme.LyricsLayout
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NowPlayingLayout
import com.example.util.AudioDeviceType
import com.example.util.AudioRouteInfo
import com.example.util.AudioTechnicalInfo
import com.example.util.AudioTechnicalMetadataReader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NowPlayingAndLyricsLayoutsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking {
            UserPreferencesRepository.getInstance(context).clearAllForTesting()
        }
        UserPreferencesRepository.resetInstanceForTesting(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            UserPreferencesRepository.getInstance(context).clearAllForTesting()
        }
        UserPreferencesRepository.resetInstanceForTesting(context)
    }

    private fun sampleSong(
        bitrate: Int = 192000,
        path: String = "/storage/emulated/0/Music/GardenSong.mp3",
        format: String = "MP3"
    ) = Song(
        id = 101L,
        title = "Garden Song",
        artist = "Phoebe Bridgers",
        album = "Punisher",
        durationMs = 219000L,
        path = path,
        contentUriString = "file://$path",
        bitrate = bitrate,
        format = format
    )

    private fun sampleSyncedLyrics(): Lyrics = Lyrics(
        songId = 101L,
        plainLyrics = "[00:02.00]Someday I'm gonna live in your house up on the hill\n" +
            "[00:07.00]And when your skinhead neighbor goes missing\n" +
            "[00:12.00]I'll plant a garden in the yard then"
    )

    @Test
    fun testLyricsLayoutEnumHasThreeOptionsAndFluidGlowDefaultFallback() {
        assertEquals(3, LyricsLayout.entries.size)
        assertEquals(LyricsLayout.FLUID_GLOW, LyricsLayout.fromId(null))
        assertEquals(LyricsLayout.FLUID_GLOW, LyricsLayout.fromId("UNKNOWN_STYLE"))
        assertEquals(LyricsLayout.FLUID_GLOW, LyricsLayout.fromId("KARAOKE_SPOTLIGHT"))
        assertEquals(LyricsLayout.CLASSIC_DEFAULT, LyricsLayout.fromId("classic_default"))
        assertEquals(LyricsLayout.FLUID_GLOW, LyricsLayout.fromId("fluid_glow"))
        assertEquals(LyricsLayout.EDITORIAL_TIMELINE, LyricsLayout.fromId("EDITORIAL_TIMELINE"))
        assertEquals(NowPlayingLayout.NEON_WAVEFORM, NowPlayingLayout.fromId(null))
    }

    @Test
    fun testAudioTechnicalInfoFormattingAndBitrateNormalization() {
        assertEquals(128, AudioTechnicalMetadataReader.normalizeBitrateToKbps(128000))
        assertEquals(192, AudioTechnicalMetadataReader.normalizeBitrateToKbps(192000))
        assertEquals(320, AudioTechnicalMetadataReader.normalizeBitrateToKbps(320))
        assertEquals(0, AudioTechnicalMetadataReader.normalizeBitrateToKbps(-1))

        assertEquals("FLAC", AudioTechnicalMetadataReader.resolveFormatLabel("audio/flac", "/music/a.flac", null))
        assertEquals("MP3", AudioTechnicalMetadataReader.resolveFormatLabel("audio/mpeg", "/music/a.mp3", null))
        assertEquals("AAC", AudioTechnicalMetadataReader.resolveFormatLabel("audio/mp4a-latm", "/music/a.m4a", null))

        val hires = AudioTechnicalInfo(
            formatLabel = "FLAC",
            sampleRateHz = 96000,
            bitrateKbps = 1411,
            bitDepth = 24,
            channelCount = 2
        )
        assertEquals("FLAC • 24-bit • 96 kHz • 1411 kbps", hires.formatSummary())

        val mp3 = AudioTechnicalInfo(
            formatLabel = "MP3",
            sampleRateHz = 44100,
            bitrateKbps = 192,
            bitDepth = 0,
            channelCount = 2
        )
        assertEquals("MP3 • 44.1 kHz • 192 kbps", mp3.formatSummary())

        val wavMono = AudioTechnicalInfo(
            formatLabel = "WAV",
            sampleRateHz = 48000,
            bitrateKbps = 1536,
            bitDepth = 16,
            channelCount = 1
        )
        assertEquals("WAV • 16-bit • 48 kHz • 1536 kbps • Mono", wavMono.formatSummary())
    }

    @Test
    fun testAudioTechnicalMetadataReaderHandlesMissingFileGracefully() = runBlocking {
        AudioTechnicalMetadataReader.clearCache()
        val info = AudioTechnicalMetadataReader.readForSong(context, sampleSong(bitrate = 128000))
        assertEquals("MP3", info.formatLabel)
        assertEquals(128, info.bitrateKbps)
    }

    @Test
    fun testPreferencesAndCloudSyncPersistLyricsLayoutVolumeBarAndAdvancedInfo() = runBlocking {
        val repo = UserPreferencesRepository.getInstance(context)
        assertEquals("FLUID_GLOW", repo.lyricsLayout.first())
        assertEquals("NEON_WAVEFORM", repo.nowPlayingLayout.first())
        assertEquals("SNAKE", repo.waveformStyle.first())
        assertFalse(repo.showNowPlayingVolumeBar.first())
        assertFalse(repo.showAdvancedSongInfo.first())

        repo.setLyricsLayout(LyricsLayout.CLASSIC_DEFAULT.id)
        repo.setShowNowPlayingVolumeBar(true)
        repo.setShowAdvancedSongInfo(true)

        UserPreferencesRepository.resetInstanceForTesting()
        val reloaded = UserPreferencesRepository.getInstance(context)
        assertEquals("CLASSIC_DEFAULT", reloaded.lyricsLayout.first())
        assertTrue(reloaded.showNowPlayingVolumeBar.first())
        assertTrue(reloaded.showAdvancedSongInfo.first())

        val cloud = CloudUserSettings(
            lyricsLayout = LyricsLayout.EDITORIAL_TIMELINE.id,
            showNowPlayingVolumeBar = true,
            showAdvancedSongInfo = true
        )
        val roundTripped = CloudUserSettings.fromMap(cloud.toMap())
        assertEquals("EDITORIAL_TIMELINE", roundTripped.lyricsLayout)
        assertTrue(roundTripped.showNowPlayingVolumeBar)
        assertTrue(roundTripped.showAdvancedSongInfo)
    }

    @Test
    fun testSettingsScreenShowsFourthLyricsUiBoxAndBothNewToggles() {
        val selectedLyrics = AtomicReference("CLASSIC_DEFAULT")
        val volumeBarToggled = AtomicBoolean(false)
        val advancedInfoToggled = AtomicBoolean(false)

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    folders = emptyList(),
                    sleepTimerState = SleepTimerState(),
                    lyricsLayout = selectedLyrics.get(),
                    onSetLyricsLayout = { selectedLyrics.set(it) },
                    showNowPlayingVolumeBar = false,
                    onSetShowNowPlayingVolumeBar = { volumeBarToggled.set(it) },
                    showAdvancedSongInfo = false,
                    onSetShowAdvancedSongInfo = { advancedInfoToggled.set(it) },
                    initialTargetSection = "UI"
                )
            }
        }
        composeTestRule.waitForIdle()

        // Verify 4th pill "Lyrics UI" exists alongside Now Playing, Home Screen, Playlists
        composeTestRule.onNodeWithText("Lyrics UI").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Verify Classic Default plus Fluid Glow and Studio Timeline render, and Karaoke Stage is removed
        composeTestRule.onNodeWithText("Classic Bento").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Fluid Glow").assertExists()
        composeTestRule.onNodeWithText("Karaoke Stage").assertDoesNotExist()
        composeTestRule.onNodeWithTag("lyrics_layout_preview_row").performScrollToNode(hasText("Studio Timeline"))
        composeTestRule.onNodeWithText("Studio Timeline").assertExists().performClick()
        composeTestRule.waitForIdle()
        assertEquals(LyricsLayout.EDITORIAL_TIMELINE.id, selectedLyrics.get())

        // Scroll directly to the two new UI toggles in UI & Themes section
        composeTestRule.onNodeWithText("Include Volume Seek bar in Now-Playing screen")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue(volumeBarToggled.get())

        composeTestRule.onNodeWithText("Display advanced song information in the Now-Playing screen")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue(advancedInfoToggled.get())
    }

    @Test
    fun testVolumeBarAndSelectedWaveformApplyAcrossAllFourNowPlayingLayoutsIncludingNeonStudio() {
        val layouts = listOf(
            NowPlayingLayout.MODERN_BENTO.id,
            NowPlayingLayout.VINYL_TURNTABLE.id,
            NowPlayingLayout.MINIMAL_GLASS.id,
            NowPlayingLayout.NEON_WAVEFORM.id
        )

        val currentLayout = androidx.compose.runtime.mutableStateOf(layouts.first())
        val currentWaveform = androidx.compose.runtime.mutableStateOf("BARS")
        val showVol = androidx.compose.runtime.mutableStateOf(true)
        val song = sampleSong()

        composeTestRule.setContent {
            MyApplicationTheme {
                NowPlayingScreen(
                    playbackState = PlaybackState(
                        currentSong = song,
                        isPlaying = true,
                        positionMs = 2000L,
                        durationMs = 219000L,
                        queue = listOf(song)
                    ),
                    currentLyrics = sampleSyncedLyrics(),
                    audioRouteInfo = AudioRouteInfo(
                        name = "Phone Speaker",
                        type = AudioDeviceType.SPEAKER,
                        icon = Icons.Default.Speaker
                    ),
                    sleepTimerState = SleepTimerState(),
                    waveformStyle = currentWaveform.value,
                    nowPlayingLayout = currentLayout.value,
                    lyricsLayout = LyricsLayout.CLASSIC_DEFAULT.id,
                    showVolumeBar = showVol.value,
                    showAdvancedSongInfo = true,
                    onDismiss = {},
                    onTogglePlayPause = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                    onSeekTo = {},
                    onFavoriteToggle = {},
                    onToggleShuffle = {},
                    onToggleRepeat = {},
                    onCustomLrcSelected = {}
                )
            }
        }

        for (layoutId in layouts) {
            currentLayout.value = layoutId
            showVol.value = true
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("now_playing_volume_seekbar", useUnmergedTree = true).assertExists()

            if (layoutId == NowPlayingLayout.NEON_WAVEFORM.id) {
                for (wf in listOf("SLEEK_LINE", "SNAKE", "BARS", "DOTS")) {
                    currentWaveform.value = wf
                    composeTestRule.waitForIdle()
                    composeTestRule.onNodeWithTag("neon_studio_waveform_visualizer", useUnmergedTree = true).assertExists()
                }
            }

            showVol.value = false
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("now_playing_volume_seekbar", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun testAlternateLyricsLayoutsAllExposeSearchOffsetAndTranscribeControls() {
        val layouts = listOf(
            LyricsLayout.FLUID_GLOW,
            LyricsLayout.EDITORIAL_TIMELINE
        )
        val currentStyle = androidx.compose.runtime.mutableStateOf(LyricsLayout.FLUID_GLOW)
        val song = sampleSong()

        composeTestRule.setContent {
            MyApplicationTheme {
                AlternateLyricsPanel(
                    lyricsLayout = currentStyle.value,
                    song = song,
                    currentLyrics = sampleSyncedLyrics(),
                    playbackState = PlaybackState(
                        currentSong = song,
                        isPlaying = true,
                        positionMs = 4000L,
                        durationMs = 219000L
                    ),
                    positionMs = 4000L,
                    showLyricsView = true,
                    isLandscape = false,
                    userIsScrollingLyrics = false,
                    lyricsAutoScroll = true,
                    onUserIsScrollingChange = {},
                    onSeekTo = {},
                    onTogglePlayPause = {},
                    onForceFetchLyrics = {},
                    onOpenSearchLyrics = {},
                    onPickLrcFile = {},
                    onCloseLyrics = {},
                    coroutineScope = androidx.compose.runtime.rememberCoroutineScope(),
                    isTranscribeTranslateEnabled = true
                )
            }
        }

        for (style in layouts) {
            currentStyle.value = style
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("lyrics_top_bar_search_button").assertExists()
            composeTestRule.onNodeWithTag("lyrics_offset_tab_button").assertExists()
            composeTestRule.onNodeWithTag("lyrics_transcribe_translate_button").assertExists()
            composeTestRule.onNodeWithTag("lyrics_close_button").assertExists()
        }
    }

    @Test
    fun testSleepTimerDialogEnforcesPendingTimeAndTrackExtensionLimits() {
        val timerState = androidx.compose.runtime.mutableStateOf(
            SleepTimerState(
                isActive = true,
                mode = "TIME",
                remainingSeconds = 175L * 60L,
                initialMinutes = 175
            )
        )

        composeTestRule.setContent {
            MyApplicationTheme(themeMode = "AMOLED") {
                com.example.ui.components.SleepTimerDialog(
                    timerState = timerState.value,
                    onStartTimerMinutes = { _, _ -> },
                    onStartSongCount = {},
                    onAddMinutes = {},
                    onAddSongs = {},
                    onCancelTimer = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // At 175m pending: +5m enabled, +10m/+15m/+30m disabled
        composeTestRule.onNodeWithTag("sleep_timer_add_min_5").assertIsEnabled()
        composeTestRule.onNodeWithTag("sleep_timer_add_min_10").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("sleep_timer_add_min_15").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("sleep_timer_add_min_30").assertIsNotEnabled()

        // At 180m pending: all time extension buttons disabled
        timerState.value = SleepTimerState(
            isActive = true,
            mode = "TIME",
            remainingSeconds = 180L * 60L,
            initialMinutes = 180
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sleep_timer_add_min_5").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("sleep_timer_add_min_10").assertIsNotEnabled()

        // At 49 pending tracks (even with totalSongs = 60!): +1 enabled, +2/+5 disabled
        timerState.value = SleepTimerState(
            isActive = true,
            isTrackTimerActive = true,
            mode = "SONGS",
            totalSongs = 60,
            targetTrackCount = 60,
            elapsedSongs = 12,
            remainingSongs = 49
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sleep_timer_add_song_1").assertIsEnabled()
        composeTestRule.onNodeWithTag("sleep_timer_add_song_2").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("sleep_timer_add_song_5").assertIsNotEnabled()

        // At 50 pending tracks: all track extension buttons disabled
        timerState.value = timerState.value.copy(remainingSongs = 50)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sleep_timer_add_song_1").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("sleep_timer_add_song_2").assertIsNotEnabled()
    }
}

