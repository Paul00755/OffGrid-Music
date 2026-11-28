package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.cast.CastConnectionState
import com.example.cast.CastDevice
import com.example.cast.CastDeviceKind
import com.example.cast.CastProtocol
import com.example.cast.CastSessionState
import com.example.data.model.Song
import com.example.jam.JamFeatureController
import com.example.jam.JamRepository
import com.example.jam.JamTrack
import com.example.jam.jamLibraryMatchKeys
import com.example.service.PlaybackManager
import com.example.service.PlaybackState
import com.example.service.SleepTimerState
import com.example.ui.components.AudioOutputSwitcherSheet
import com.example.ui.components.CastDeviceSheet
import com.example.ui.screens.NowPlayingScreen
import com.example.ui.screens.layouts.ensureNeonDarkStage
import com.example.ui.screens.layouts.ensureNeonPastelStage
import com.example.ui.theme.LyricsLayout
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NowPlayingLayout
import com.example.ui.theme.isMonochromeNeutral
import com.example.ui.theme.toPastelAccent
import com.example.ui.theme.toPastelBackground
import com.example.ui.theme.toPastelCompanionHue
import com.example.ui.theme.toPastelSurface
import com.example.ui.theme.toPastelSurfaceVariant
import com.example.ui.theme.toReadableLightAccent
import com.example.ui.theme.toRichDarkAccent
import com.example.ui.theme.toRichDarkCompanionHue
import com.example.util.ArtworkColorExtractor
import com.example.util.AudioDeviceType
import com.example.util.AudioRouteDevice
import com.example.util.AudioRouteInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CastThemePlayAllJamFixesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun colorSaturation(color: Color): Float {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color.toArgb(), hsv)
        return hsv[1]
    }

    private fun colorHue(color: Color): Float {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color.toArgb(), hsv)
        return hsv[0]
    }

    private fun angularHueDistance(h1: Float, h2: Float): Float {
        val d = abs(h1 - h2)
        return minOf(d, 360f - d)
    }

    private fun createMonochromeBitmapWithColoredBottomWatermark(width: Int = 80, height: Int = 80): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gray = ((x * 2 + y * 2) % 220) + 18
                bitmap.setPixel(x, y, AndroidColor.rgb(gray, gray, gray))
            }
        }
        // Paint a vibrant purple/magenta watermark badge in the bottom 12% strip (like MASSTAMILAN.FM)
        val badgeTop = (height * 0.88f).toInt()
        for (y in badgeTop until height) {
            for (x in (width * 0.08f).toInt() until (width * 0.40f).toInt()) {
                bitmap.setPixel(x, y, AndroidColor.rgb(210, 40, 235))
            }
        }
        return bitmap
    }

    private fun createBlueTopWarmBrownBottomBitmap(width: Int = 80, height: Int = 80): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val splitY = height / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (y < splitY) {
                    // Rich cerulean / cobalt blue sky in upper half
                    bitmap.setPixel(x, y, AndroidColor.rgb(32, 118, 214))
                } else {
                    // Rich warm earthy brown in lower half
                    bitmap.setPixel(x, y, AndroidColor.rgb(154, 92, 46))
                }
            }
        }
        return bitmap
    }

    @Test
    fun monochromeArtworkWithColoredWatermarkStaysPureNeutralAcrossExtractorAndThemeHelpers() {
        val bitmap = createMonochromeBitmapWithColoredBottomWatermark()
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

            assertTrue(
                "B&W artwork with bottom watermark must be classified as monochrome",
                ArtworkColorExtractor.isMonochromeArtwork(pixels, bitmap.width, bitmap.height)
            )

            val prominent = ArtworkColorExtractor.findProminentColor(bitmap)
            val bottomStage = ArtworkColorExtractor.findBottomStageColor(bitmap)
            val dual = ArtworkColorExtractor.findDualGradientColors(bitmap)

            assertNotNull(prominent)
            assertNotNull(bottomStage)
            assertNotNull(dual)

            assertTrue("Prominent color must stay neutral", prominent!!.isMonochromeNeutral())
            assertTrue("Bottom stage color must stay neutral", bottomStage!!.isMonochromeNeutral())
            assertTrue("Dual top color must stay neutral", dual!!.first.isMonochromeNeutral())
            assertTrue("Dual bottom color must stay neutral", dual.second.isMonochromeNeutral())

            val neutralSeed = prominent
            val derivedColors = listOf(
                neutralSeed.toPastelBackground(),
                neutralSeed.toPastelSurface(),
                neutralSeed.toPastelSurfaceVariant(),
                neutralSeed.toPastelAccent(),
                neutralSeed.toPastelCompanionHue(),
                neutralSeed.toReadableLightAccent(),
                neutralSeed.toRichDarkAccent(),
                neutralSeed.toRichDarkCompanionHue(),
                dual.first.ensureNeonPastelStage(),
                dual.second.ensureNeonPastelStage(),
                dual.first.ensureNeonDarkStage(),
                dual.second.ensureNeonDarkStage()
            )
            derivedColors.forEachIndexed { idx, c ->
                assertTrue(
                    "Derived theme color #$idx must remain monochrome (sat=${colorSaturation(c)})",
                    colorSaturation(c) < 0.05f
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun dualGradientExtractorPreservesBlueTopAndWarmBrownBottomForNeonStudio() {
        val bitmap = createBlueTopWarmBrownBottomBitmap()
        try {
            val dual = ArtworkColorExtractor.findDualGradientColors(bitmap)
            assertNotNull(dual)
            val (topColor, bottomColor) = dual!!

            assertFalse("Top color should not be monochrome", topColor.isMonochromeNeutral())
            assertFalse("Bottom color should not be monochrome", bottomColor.isMonochromeNeutral())

            val topHue = colorHue(topColor)
            val bottomHue = colorHue(bottomColor)
            val separation = angularHueDistance(topHue, bottomHue)

            assertTrue("Top hue should be blue (~190..250 deg), was $topHue", topHue in 185f..255f)
            assertTrue("Bottom hue should be warm brown (~10..50 deg), was $bottomHue", bottomHue in 8f..55f)
            assertTrue("Top and bottom hues must stay distinctly separated (sep=$separation)", separation >= 110f)

            val pastelTop = topColor.ensureNeonPastelStage()
            val pastelBottom = bottomColor.ensureNeonPastelStage()
            val pastelSep = angularHueDistance(colorHue(pastelTop), colorHue(pastelBottom))
            assertTrue("Neon pastel stage must preserve dual hue contrast (sep=$pastelSep)", pastelSep >= 90f)

            val darkTop = topColor.ensureNeonDarkStage()
            val darkBottom = bottomColor.ensureNeonDarkStage()
            val darkSep = angularHueDistance(colorHue(darkTop), colorHue(darkBottom))
            assertTrue("Neon dark stage must preserve dual hue contrast (sep=$darkSep)", darkSep >= 90f)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun jamClockCalibrationFiltersOutliersAndPllLocksTightlyWithinDeadband() {
        // First probe is a cold 180ms RTT spike; later probes cluster cleanly around offset +420ms with low RTT
        // Each pair is (offsetMs to rttMs)
        val probes = listOf(
            510L to 180L, // cold first probe -> discarded
            422L to 34L,
            419L to 26L,
            420L to 24L,
            421L to 28L,
            340L to 155L  // network jitter outlier -> discarded
        )
        val result = JamRepository.aggregateClockCalibrationProbes(probes)
        assertNotNull(result)
        val (offsetMs, bestRttMs) = result!!
        assertEquals(24L, bestRttMs)
        assertTrue("Aggregated offset should converge near 420ms (was $offsetMs)", offsetMs in 418L..422L)

        // Tight deadband (±5ms) -> pure 1.0x speed
        assertEquals(1.0f, JamFeatureController.computeJamPllSpeed(0L), 0.0001f)
        assertEquals(1.0f, JamFeatureController.computeJamPllSpeed(5L), 0.0001f)
        assertEquals(1.0f, JamFeatureController.computeJamPllSpeed(-5L), 0.0001f)

        // Behind reference (+12ms, +45ms, +120ms) -> proportional catch-up capped at 1.05x
        val speedSmallBehind = JamFeatureController.computeJamPllSpeed(12L)
        val speedMediumBehind = JamFeatureController.computeJamPllSpeed(45L)
        val speedLargeBehind = JamFeatureController.computeJamPllSpeed(120L)
        assertTrue(speedSmallBehind > 1.0f && speedSmallBehind < speedMediumBehind)
        assertTrue(speedMediumBehind <= speedLargeBehind && speedLargeBehind <= 1.05f)

        // Ahead of reference (-12ms, -45ms, -120ms) -> proportional slow-down floored at 0.95x
        val speedSmallAhead = JamFeatureController.computeJamPllSpeed(-12L)
        val speedMediumAhead = JamFeatureController.computeJamPllSpeed(-45L)
        val speedLargeAhead = JamFeatureController.computeJamPllSpeed(-120L)
        assertTrue(speedSmallAhead < 1.0f && speedSmallAhead > speedMediumAhead)
        assertTrue(speedMediumAhead >= speedLargeAhead && speedLargeAhead >= 0.95f)
    }

    @Test
    fun jamCrossDeviceMatchingHandlesWatermarksBitratesAndSwappedArtists() {
        val salt = "session_salt_42"

        val deviceATrack1 = JamTrack(
            sharedTrackId = "track_a1",
            title = "Pagal Iravai - MassTamilan.fm",
            artist = "Pranav Das",
            album = "Maraigirai",
            durationMs = 288_000L
        )
        val deviceBTrack1 = JamTrack(
            sharedTrackId = "track_b1",
            title = "01 - Pagal Iravai (From Maraigirai) [320kbps]",
            artist = "Pranav Das",
            album = "Maraigirai (Original Motion Picture Soundtrack)",
            durationMs = 289_500L
        )
        val keysA1 = jamLibraryMatchKeys(deviceATrack1, audioFingerprint = null, sessionSalt = salt).toSet()
        val keysB1 = jamLibraryMatchKeys(deviceBTrack1, audioFingerprint = null, sessionSalt = salt).toSet()
        assertTrue("Watermarked and bitrate-tagged variants must share match keys", keysA1.intersect(keysB1).isNotEmpty())

        val deviceATrack2 = JamTrack(
            sharedTrackId = "track_a2",
            title = "Aasa Kooda (Official Audio)",
            artist = "Sai Abhyankkar, Sai Smriti - MassTamilan.dev",
            album = "Aasa Kooda",
            durationMs = 215_000L
        )
        val deviceBTrack2 = JamTrack(
            sharedTrackId = "track_b2",
            title = "Aasa Kooda",
            artist = "Sai Smriti & Sai Abhyankkar",
            album = "Indie Single",
            durationMs = 216_500L
        )
        val keysA2 = jamLibraryMatchKeys(deviceATrack2, audioFingerprint = null, sessionSalt = salt).toSet()
        val keysB2 = jamLibraryMatchKeys(deviceBTrack2, audioFingerprint = null, sessionSalt = salt).toSet()
        assertTrue("Swapped multi-artist tracks must share match keys", keysA2.intersect(keysB2).isNotEmpty())
    }

    @Test
    fun castDeviceSheetRemovesTvLyricsButtonAndLocksAudioOutputStatus() {
        val tv = CastDevice(
            id = "gcast_living_room",
            name = "sampaul",
            subtitle = "Google Cast",
            protocol = CastProtocol.GOOGLE_CAST,
            kind = CastDeviceKind.TV,
            mediaRouteId = "route_tv"
        )
        val song = Song(
            id = 7L,
            title = "Aasa Kooda",
            artist = "Sai Abhyankkar, Sai Smriti",
            album = "Indie",
            durationMs = 215_000L,
            path = "/music/aasa.mp3",
            contentUriString = "file:///music/aasa.mp3"
        )
        val castState = CastSessionState(
            connectionState = CastConnectionState.CONNECTED,
            connectedDevice = tv,
            discoveredDevices = listOf(tv),
            isWifiConnected = true,
            isRemotePlaying = true,
            remotePositionMs = 45_000L,
            remoteDurationMs = 215_000L,
            remoteVolume = 0.35f,
            tvLyricsEnabled = true
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                CastDeviceSheet(
                    castState = castState,
                    currentSong = song,
                    audioRouteInfo = AudioRouteInfo("Playing in TV", AudioDeviceType.TV_CAST, Icons.Default.Tv),
                    availableAudioDevices = emptyList(),
                    onStartScan = {},
                    onStopScan = {},
                    onSelectCastDevice = {},
                    onDisconnectCast = {},
                    onToggleRemotePlayPause = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                    onSeekRemote = {},
                    onSetRemoteVolume = {},
                    onToggleTvLyrics = {},
                    onQuickPlayOnCast = {},
                    onSelectAudioDevice = {},
                    onLaunchSystemCast = {},
                    onOpenWifiSettings = {},
                    onOpenBluetoothSettings = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_toggle_tv_lyrics").assertDoesNotExist()
        composeTestRule.onAllNodesWithText("Current output: Playing in TV", substring = true).onFirst().assertExists()
    }

    @Test
    fun audioOutputSwitcherSheetLocksOutputWhenCastingToTv() {
        var deviceSelectionInvoked = false
        var systemSwitcherInvoked = false
        val headphoneDevice = AudioRouteDevice(
            id = "bt_1",
            name = "Sony WH-1000XM5",
            description = "Bluetooth headphones",
            type = AudioDeviceType.BLUETOOTH,
            icon = Icons.Default.Headphones,
            isSelected = false
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                AudioOutputSwitcherSheet(
                    audioRouteInfo = AudioRouteInfo("Playing in TV", AudioDeviceType.TV_CAST, Icons.Default.Tv),
                    availableDevices = listOf(headphoneDevice),
                    onSelectDevice = { deviceSelectionInvoked = true },
                    onLaunchSystemSwitcher = { systemSwitcherInvoked = true },
                    onDismiss = {},
                    isCastingToTv = true,
                    castDeviceName = "sampaul"
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("audio_output_locked_tv_banner").assertExists()
        composeTestRule.onNodeWithTag("btn_system_audio_switcher").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("audio_device_bt_1").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("audio_device_bt_1").performClick()
        assertFalse("Device switching must be locked while casting", deviceSelectionInvoked)
        assertFalse("System switcher must be locked while casting", systemSwitcherInvoked)
    }

    @Test
    fun allFourNowPlayingLayoutsShowPlayingInTvStatusWhenCasting() {
        val layouts = listOf(
            NowPlayingLayout.MODERN_BENTO.id,
            NowPlayingLayout.VINYL_TURNTABLE.id,
            NowPlayingLayout.MINIMAL_GLASS.id,
            NowPlayingLayout.NEON_WAVEFORM.id
        )
        val currentLayout = mutableStateOf(layouts.first())
        val song = Song(
            id = 12L,
            title = "Siriki",
            artist = "Senthil Ganesh",
            album = "Kaappaan",
            durationMs = 282_000L,
            path = "/music/siriki.mp3",
            contentUriString = "file:///music/siriki.mp3"
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                NowPlayingScreen(
                    playbackState = PlaybackState(
                        currentSong = song,
                        isPlaying = false,
                        positionMs = 12_000L,
                        durationMs = 282_000L,
                        queue = listOf(song)
                    ),
                    currentLyrics = null,
                    audioRouteInfo = AudioRouteInfo(
                        name = "Playing in TV",
                        type = AudioDeviceType.TV_CAST,
                        icon = Icons.Default.Tv
                    ),
                    sleepTimerState = SleepTimerState(),
                    waveformStyle = "SNAKE",
                    nowPlayingLayout = currentLayout.value,
                    lyricsLayout = LyricsLayout.CLASSIC_DEFAULT.id,
                    showVolumeBar = false,
                    showAdvancedSongInfo = false,
                    isCastingToTv = true,
                    castDeviceName = "sampaul",
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
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("now_playing_casting_tv_status", useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun playQueueWithForceShuffleFalseRespectsExactCustomOrderEvenWhenShuffleEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PlaybackManager.getInstance(context)
        val orderedPlaylist = (1L..12L).map { idx ->
            Song(
                id = 500L + idx,
                title = "Track $idx",
                artist = "Artist $idx",
                album = "Custom Playlist",
                durationMs = 180_000L,
                path = "/music/track_$idx.mp3",
                contentUriString = "file:///music/track_$idx.mp3"
            )
        }

        // First enable shuffle mode via explicit shuffle queue
        manager.playQueue(
            songs = orderedPlaylist,
            startIndex = 0,
            sourceContext = "PLAYLIST_SHUFFLE",
            forceShuffle = true,
            startPlayback = false
        )
        assertTrue("Shuffle mode should be active", manager.playbackState.value.isShuffleEnabled)

        // Now invoke Play All behavior (forceShuffle = false)
        manager.playQueue(
            songs = orderedPlaylist,
            startIndex = 0,
            sourceContext = "PLAYLIST",
            forceShuffle = false,
            startPlayback = false
        )

        val state = manager.playbackState.value
        assertFalse("Play All must turn shuffle off for the queued playlist", state.isShuffleEnabled)
        assertEquals(
            "Play All must preserve exact custom playlist order",
            orderedPlaylist.map { it.id },
            state.queue.map { it.id }
        )
        assertEquals(orderedPlaylist.first().id, state.currentSong?.id)
    }
}
