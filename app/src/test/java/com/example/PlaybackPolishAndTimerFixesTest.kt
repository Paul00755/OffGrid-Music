package com.example

import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.core.app.ApplicationProvider
import com.example.service.AudioFocusManager
import com.example.service.AudioNormalizerProcessor
import com.example.service.NormalizationCache
import com.example.service.QueueLoudnessAnalyzer
import com.example.service.SleepTimerState
import com.example.ui.components.SleepTimerDialog
import com.example.ui.theme.MyApplicationTheme
import com.example.widget.WidgetBitmapRenderer
import com.example.widget.WidgetUpdateManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackPolishAndTimerFixesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        NormalizationCache.init(context)
        NormalizationCache.clear()
    }

    @After
    fun tearDown() {
        NormalizationCache.clear()
    }

    @Test
    fun sleepTimerDialog_rendersClockDialFinishLastTrackAndTrackTab() {
        var finishLastToggled = false
        var startedMinutes = -1
        var startedTracks = -1

        composeTestRule.setContent {
            MyApplicationTheme {
                SleepTimerDialog(
                    timerState = SleepTimerState(isActive = false, finishLastSong = false),
                    onStartTimerMinutes = { mins, _ -> startedMinutes = mins },
                    onStartSongCount = { count -> startedTracks = count },
                    onSetFinishLastSong = { finishLastToggled = it },
                    onCancelTimer = {},
                    onDismiss = {}
                )
            }
        }

        // Verify redesigned radial clock dial, STOPS AT readout, and "Finish last track" copy
        composeTestRule.onNodeWithTag("sleep_timer_time_dial").assertExists()
        composeTestRule.onNodeWithTag("sleep_timer_stops_at_text").assertExists()
        composeTestRule.onNodeWithText("Finish last track").assertExists()
        composeTestRule.onNodeWithText("Don't cut off mid-track").assertExists()
        composeTestRule.onNodeWithTag("sleep_timer_custom_time_set_button").assertExists()

        // Toggle Finish last track switch
        composeTestRule.onNodeWithTag("sleep_timer_finish_last_track_switch").performScrollTo().performClick()
        assertTrue(finishLastToggled)

        // Start via radial dial CTA (default 30m)
        composeTestRule.onNodeWithTag("sleep_timer_dial_start_button").performScrollTo().performClick()
        assertEquals(30, startedMinutes)

        // Switch to By Tracks tab and verify matching track radial dial & start button
        composeTestRule.onNodeWithTag("sleep_timer_tab_tracks").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sleep_timer_track_dial").assertExists()
        composeTestRule.onNodeWithTag("sleep_timer_tracks_caption").assertExists()
        composeTestRule.onNodeWithTag("sleep_timer_custom_track_set_button").assertExists()
        composeTestRule.onNodeWithTag("sleep_timer_track_dial_start_button").performScrollTo().performClick()
        assertEquals(5, startedTracks)
    }

    @Test
    fun widgetBitmapRenderer_brokenHeartStagesProduceDistinctBitmapsAndGuardHolds() {
        val normalBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = 96,
            isFavorite = false,
            accentColor = 0xFFFF5252.toInt(),
            animFrame = WidgetBitmapRenderer.FavoriteAnimFrame.NORMAL
        )
        val brokenBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = 96,
            isFavorite = false,
            accentColor = 0xFFFF5252.toInt(),
            animFrame = WidgetBitmapRenderer.FavoriteAnimFrame.BROKEN
        )
        val brokenSplitBmp = WidgetBitmapRenderer.renderFavoriteChipButton(
            sizePx = 96,
            isFavorite = false,
            accentColor = 0xFFFF5252.toInt(),
            animFrame = WidgetBitmapRenderer.FavoriteAnimFrame.BROKEN_SPLIT
        )

        assertEquals(96, normalBmp.width)
        assertEquals(96, brokenBmp.width)
        assertEquals(96, brokenSplitBmp.width)
        assertNotSame("BROKEN bitmap must be cached separately from NORMAL", normalBmp, brokenBmp)
        assertNotSame("BROKEN_SPLIT bitmap must be cached separately from BROKEN", brokenBmp, brokenSplitBmp)
        assertNotSame("BROKEN_SPLIT bitmap must be cached separately from NORMAL", normalBmp, brokenSplitBmp)

        // Triggering unfavorite transition animation immediately arms the BROKEN animation guard
        WidgetUpdateManager.playFavoriteTransitionAnimation(context, songId = 998877L, isFavorite = false)
        assertEquals(
            WidgetBitmapRenderer.FavoriteAnimFrame.BROKEN,
            WidgetUpdateManager.resolveFavoriteAnimFrame(998877L)
        )
        // Other songs remain unaffected
        assertEquals(
            WidgetBitmapRenderer.FavoriteAnimFrame.NORMAL,
            WidgetUpdateManager.resolveFavoriteAnimFrame(112233L)
        )

        // Wait briefly for widgetScope coroutine stage 1 (150ms) -> stage 2 BROKEN_SPLIT
        var sawSplit = false
        val splitDeadline = System.currentTimeMillis() + 900L
        while (System.currentTimeMillis() < splitDeadline) {
            if (WidgetUpdateManager.resolveFavoriteAnimFrame(998877L) == WidgetBitmapRenderer.FavoriteAnimFrame.BROKEN_SPLIT) {
                sawSplit = true
                break
            }
            Thread.sleep(20L)
        }
        assertTrue("Widget unfavorite animation should progress to BROKEN_SPLIT", sawSplit)

        // Wait for stage 2 (+220ms) -> settles cleanly back to NORMAL
        var sawNormal = false
        val normalDeadline = System.currentTimeMillis() + 900L
        while (System.currentTimeMillis() < normalDeadline) {
            if (WidgetUpdateManager.resolveFavoriteAnimFrame(998877L) == WidgetBitmapRenderer.FavoriteAnimFrame.NORMAL) {
                sawNormal = true
                break
            }
            Thread.sleep(20L)
        }
        assertTrue("Widget unfavorite animation should settle back to NORMAL", sawNormal)
    }

    @Test
    fun notificationIcon_loadsCustomOffGridVectorDrawableCleanly() {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_notification)
        assertNotNull("ic_notification must load cleanly", drawable)
        assertTrue("ic_notification must have valid intrinsic dimensions", drawable!!.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
    }

    @Test
    fun queueLoudnessAnalyzer_levelsQuietAndHotTracksWhileProtectingTruePeaks() {
        // Quiet track (-22 LUFS, moderate peak 0.35) gets lifted cleanly toward -14 LUFS (~+8dB, capped at max 2.25x)
        val quietGain = QueueLoudnessAnalyzer.computeOptimalLinearGain(
            integratedLufs = -22.0f,
            truePeakLinear = 0.35f
        )
        assertTrue("Quiet track should be boosted (>1.8x), got $quietGain", quietGain > 1.8f)
        assertTrue("Quiet track peak must stay below ceiling", quietGain * 0.35f <= QueueLoudnessAnalyzer.TRUE_PEAK_CEILING + 0.001f)

        // Hot track (-6 LUFS, peak 0.99) gets attenuated down to -14 LUFS (~0.398x)
        val hotGain = QueueLoudnessAnalyzer.computeOptimalLinearGain(
            integratedLufs = -6.0f,
            truePeakLinear = 0.99f
        )
        assertTrue("Hot track should be attenuated (<0.5x), got $hotGain", hotGain < 0.5f)
        assertTrue("Hot track stays above min gain bound", hotGain >= QueueLoudnessAnalyzer.MIN_LINEAR_GAIN)

        // Dynamic classical track (-22 LUFS, sharp peak 0.80) MUST respect true-peak headroom (0.94 / 0.80 = 1.175)
        val dynamicGain = QueueLoudnessAnalyzer.computeOptimalLinearGain(
            integratedLufs = -22.0f,
            truePeakLinear = 0.80f
        )
        assertTrue("Dynamic peak protection must prevent clipping", dynamicGain * 0.80f <= QueueLoudnessAnalyzer.TRUE_PEAK_CEILING + 0.001f)
        assertEquals(0.94f / 0.80f, dynamicGain, 0.01f)
    }

    @Test
    fun audioNormalizerProcessor_supportsPcm16AndPcmFloatWithLockedConstantGain() {
        val trackKey = QueueLoudnessAnalyzer.trackKeyFor(4242L, "/music/test.flac")
        NormalizationCache.putGain(trackKey, 0.50f)

        // 1. PCM_16BIT locked constant gain verification
        val proc16 = AudioNormalizerProcessor().apply {
            isEnabled = true
            setTrack(4242L, "/music/test.flac")
        }
        assertTrue("Cached track should lock gain immediately", proc16.isTrackGainLocked)
        assertEquals(0.50f, proc16.currentGain, 0.001f)

        val fmt16 = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        assertEquals(fmt16, proc16.configure(fmt16))
        proc16.flush()

        val in16 = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        in16.putShort(10000).putShort(-10000).putShort(4000).putShort(-4000)
        in16.flip()
        proc16.queueInput(in16)
        val out16 = proc16.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(5000, out16.short.toInt())
        assertEquals(-5000, out16.short.toInt())
        assertEquals(2000, out16.short.toInt())
        assertEquals(-2000, out16.short.toInt())

        // 2. PCM_FLOAT (Hi-Res 24/32-bit) locked constant gain verification
        val procFloat = AudioNormalizerProcessor().apply {
            isEnabled = true
            setTrack(4242L, "/music/test.flac")
        }
        val fmtFloat = AudioProcessor.AudioFormat(96000, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(fmtFloat, procFloat.configure(fmtFloat))
        procFloat.flush()

        val inFloat = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
        inFloat.putFloat(0.40f).putFloat(-0.40f).putFloat(0.20f).putFloat(-0.20f)
        inFloat.flip()
        procFloat.queueInput(inFloat)
        val outFloat = procFloat.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.20f, outFloat.float, 0.001f)
        assertEquals(-0.20f, outFloat.float, 0.001f)
        assertEquals(0.10f, outFloat.float, 0.001f)
        assertEquals(-0.10f, outFloat.float, 0.001f)
    }

    @Test
    fun audioFocusManager_defersPostCallResumeUntilAudioRouteSettles() {
        var playing = true
        var callActive = true
        var normalResumeCount = 0
        var postCallResumeCount = 0

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { playing },
            callActiveProvider = { callActive },
            onPauseRequested = { playing = false },
            onResumeRequested = {
                normalResumeCount++
                playing = true
            },
            onResumeAfterCallRequested = {
                postCallResumeCount++
                playing = true
            }
        )

        // Phone call arrives -> pauses playback
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("Playback should pause during phone call", playing)

        // Phone call ends & AUDIOFOCUS_GAIN arrives
        callActive = false
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        // MUST NOT resume instantaneously at 0ms while audio HAL/SCO route is tearing down
        assertEquals("Should not fire instant normal resume after phone call", 0, normalResumeCount)
        assertEquals("Should defer post-call resume until settling window completes", 0, postCallResumeCount)
        assertTrue("Post-call settling resume should be pending", focusManager.hasPendingPostCallResume())

        // Advance main looper past settling window (~600ms+) -> smooth post-call resume fires exactly once
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        assertEquals("Post-call smooth resume must fire exactly once", 1, postCallResumeCount)
        assertEquals(0, normalResumeCount)
        assertTrue("Playback should be resumed after settling", playing)

        focusManager.unregisterCameraCallback()
    }
}
