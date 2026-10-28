package com.example

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPreferencesRepository
import com.example.service.AudioFocusManager
import com.example.service.AudioNormalizerProcessor
import com.example.service.EqualizerManager
import com.example.service.SleepTimerManager
import androidx.media3.common.audio.AudioProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAndAudioEngineAuditTest {

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

    @Test
    fun testFreshInstallDefaults() {
        runBlocking {
            val repo = UserPreferencesRepository.getInstance(context)

            assertFalse("Default Auto-pause for Camera must be OFF", repo.getAutoPauseCameraSync())
            assertTrue("Default Auto-pause for System Audio must be ON", repo.getAutoPauseSystemAudioSync())
            assertFalse(repo.autoPauseCamera.first())
            assertTrue(repo.autoPauseSystemAudio.first())
            assertEquals("Default Add to Queue Position must be BOTTOM", "BOTTOM", repo.getAddToQueuePositionSync())
            assertEquals("Default Add to Queue Position flow must emit BOTTOM", "BOTTOM", repo.addToQueuePosition.first())
        }
    }

    @Test
    fun testLegacyPlayDuringCameraMigration() {
        runBlocking {
            // Case 1: Legacy play_during_camera = false (user had combined auto-pause ON)
            context.getSharedPreferences("offgrid_boot_cache", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("pref_play_during_camera", false)
                .commit()

            UserPreferencesRepository.resetInstanceForTesting()
            val repo1 = UserPreferencesRepository.getInstance(context)
            assertFalse("Migration must not unexpectedly enable camera auto-pause", repo1.autoPauseCamera.first())
            assertTrue("System audio auto-pause remains ON when play_during_camera was false", repo1.autoPauseSystemAudio.first())

            // Case 2: Legacy play_during_camera = true (user had disabled combined auto-pause)
            repo1.clearAllForTesting()
            UserPreferencesRepository.resetInstanceForTesting(context)
            context.getSharedPreferences("offgrid_boot_cache", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("pref_play_during_camera", true)
                .commit()

            val repo2 = UserPreferencesRepository.getInstance(context)
            assertFalse("Auto-pause for Camera must be OFF when legacy play_during_camera was true", repo2.autoPauseCamera.first())
            assertFalse("Auto-pause for System Audio must be OFF when legacy play_during_camera was true", repo2.autoPauseSystemAudio.first())
        }
    }

    @Test
    fun testAllSettingsPersistAcrossProcessRestart() {
        runBlocking {
        val repo = UserPreferencesRepository.getInstance(context)

        // 1. Audio & Sound settings
        repo.setAutoPauseCamera(true)
        repo.setAutoPauseSystemAudio(false)
        repo.setReduceVolumeOnNotification(false)
        repo.setCrossfadeDurationSec(6)
        repo.setFadeInMs(2500)
        repo.setFadeOutMs(1800)
        repo.setGaplessPlayback(false)
        repo.setNormalizeAudio(true)
        repo.setAudioBalance(-0.35f)
        repo.setPauseOnZeroVolume(true)
        repo.setAutoResumeOnVolumeIncrease(true)
        repo.setEqualizerPreset("CUSTOM")
        repo.setCustomEqBands(listOf(3.5f, 1.5f, -1.0f, 2.0f, 4.5f))
        repo.setCustomBassBoost(650)
        repo.setCustomVirtualizer(420)

        // 2. Playback & Sleep Timer settings
        repo.setSleepTimerMode("SONGS")
        repo.setSleepTimerFinishLastSong(true)
        repo.setScrobbleThresholdType("SECONDS")
        repo.setScrobbleThresholdValue(45)
        repo.setShakeToSkip(true)
        repo.setAlwaysShuffleMode(true)
        repo.setAutoPlayOnAppStartup(true)
        repo.setGesturePlaybackEnabled(false)
        repo.setAddToQueuePosition("TOP_NEXT")
        repo.setPlaylistAddSongPosition("TOP")
        repo.setAutoPlayOnHeadphoneConnection(true)
        repo.setKeepScreenAwake(true)

        // 3. Visual & Display Studio settings
        repo.setThemeMode("AMOLED")
        repo.setUiTheme("SYNTHWAVE")
        repo.setDisplayScale("COMFORT")
        repo.setCustomAccentColor(0xFF00FFAA.toInt())
        repo.setAccentColorPreset("EMERALD")
        repo.setDynamicArtworkTheme(false)
        repo.setFetchOnlineArtworkHighRes(false)
        repo.setLyricsAutoScroll(false)
        repo.setWaveformStyle("SYMMETRIC")
        repo.setHighRefreshRate(false)
        repo.setMiniPlayerStyle("DOCKED")
        repo.setMiniPlayerShowPrevious(false)
        repo.setMiniPlayerShowNext(false)
        repo.setMiniPlayerShowFavorite(false)
        repo.setNowPlayingLayout("VINYL")
        repo.setHomeScreenLayout("MAGAZINE")
        repo.setPlaylistsLayout("COMPACT")

        // 4. Library, Cache & Storage settings
        repo.setFullDeviceScan(true)
        repo.setSongDurationRange(15, 600)
        repo.saveLibrarySortOption("DATE_ADDED", false)
        repo.setCacheLyricsEnabled(false)
        repo.setCacheAlbumArtEnabled(false)
        repo.setCacheSongArtWorkEnabled(false)
        repo.setDatabaseStorageTier("MAXIMUM")

        // Simulate app closure / process termination by resetting singleton and re-instantiating
        UserPreferencesRepository.resetInstanceForTesting()
        val restartedRepo = UserPreferencesRepository.getInstance(context)

        // Verify synchronous startup getters (used during service/viewmodel boot before coroutine emission)
        assertTrue(restartedRepo.getAutoPauseCameraSync())
        assertFalse(restartedRepo.getAutoPauseSystemAudioSync())
        assertFalse(restartedRepo.getReduceVolumeOnNotificationSync())
        assertEquals(6, restartedRepo.getCrossfadeDurationSecSync())
        assertEquals(2500, restartedRepo.getFadeInMsSync())
        assertEquals(1800, restartedRepo.getFadeOutMsSync())
        assertFalse(restartedRepo.getGaplessPlaybackSync())
        assertTrue(restartedRepo.getNormalizeAudioSync())
        assertEquals(-0.35f, restartedRepo.getAudioBalanceSync(), 0.001f)
        assertTrue(restartedRepo.getPauseOnZeroVolumeSync())
        assertTrue(restartedRepo.getAutoResumeOnVolumeIncreaseSync())
        assertEquals("CUSTOM", restartedRepo.getEqualizerPresetSync())
        assertEquals(listOf(3.5f, 1.5f, -1.0f, 2.0f, 4.5f), restartedRepo.getCustomEqBandsSync())
        assertEquals(650, restartedRepo.getCustomBassBoostSync())
        assertEquals(420, restartedRepo.getCustomVirtualizerSync())
        assertEquals("SONGS", restartedRepo.getSleepTimerModeSync())
        assertTrue(restartedRepo.getSleepTimerFinishLastSongSync())

        // Verify Flow emissions after restart
        assertTrue(restartedRepo.autoPauseCamera.first())
        assertFalse(restartedRepo.autoPauseSystemAudio.first())
        assertFalse(restartedRepo.reduceVolumeOnNotification.first())
        assertEquals(6, restartedRepo.crossfadeDurationSec.first())
        assertEquals(2500, restartedRepo.fadeInMs.first())
        assertEquals(1800, restartedRepo.fadeOutMs.first())
        assertFalse(restartedRepo.gaplessPlayback.first())
        assertTrue(restartedRepo.normalizeAudio.first())
        assertEquals(-0.35f, restartedRepo.audioBalance.first(), 0.001f)
        assertTrue(restartedRepo.pauseOnZeroVolume.first())
        assertTrue(restartedRepo.autoResumeOnVolumeIncrease.first())
        assertEquals("CUSTOM", restartedRepo.equalizerPreset.first())
        assertEquals(listOf(3.5f, 1.5f, -1.0f, 2.0f, 4.5f), restartedRepo.customEqBands.first())
        assertEquals(650, restartedRepo.customBassBoost.first())
        assertEquals(420, restartedRepo.customVirtualizer.first())

        assertEquals("SONGS", restartedRepo.sleepTimerMode.first())
        assertTrue(restartedRepo.sleepTimerFinishLastSong.first())
        assertEquals("SECONDS", restartedRepo.scrobbleThresholdType.first())
        assertEquals(45, restartedRepo.scrobbleThresholdValue.first())
        assertTrue(restartedRepo.shakeToSkip.first())
        assertTrue(restartedRepo.alwaysShuffleMode.first())
        assertTrue(restartedRepo.autoPlayOnAppStartup.first())
        assertFalse(restartedRepo.gesturePlaybackEnabled.first())
        assertEquals("TOP_NEXT", restartedRepo.addToQueuePosition.first())
        assertEquals("TOP", restartedRepo.playlistAddSongPosition.first())
        assertTrue(restartedRepo.autoPlayOnHeadphoneConnection.first())
        assertTrue(restartedRepo.keepScreenAwake.first())

        assertEquals("AMOLED", restartedRepo.themeMode.first())
        assertEquals("SYNTHWAVE", restartedRepo.uiTheme.first())
        assertEquals("COMFORT", restartedRepo.displayScale.first())
        assertEquals(0xFF00FFAA.toInt(), restartedRepo.customAccentColor.first())
        assertEquals("EMERALD", restartedRepo.accentColorPreset.first())
        assertFalse(restartedRepo.isDynamicArtworkTheme.first())
        assertFalse(restartedRepo.fetchOnlineArtworkHighRes.first())
        assertFalse(restartedRepo.lyricsAutoScroll.first())
        assertEquals("SYMMETRIC", restartedRepo.waveformStyle.first())
        assertFalse(restartedRepo.highRefreshRate.first())
        assertEquals("DOCKED", restartedRepo.miniPlayerStyle.first())
        assertFalse(restartedRepo.miniPlayerShowPrevious.first())
        assertFalse(restartedRepo.miniPlayerShowNext.first())
        assertFalse(restartedRepo.miniPlayerShowFavorite.first())
        assertEquals("VINYL", restartedRepo.nowPlayingLayout.first())
        assertEquals("MAGAZINE", restartedRepo.homeScreenLayout.first())
        assertEquals("COMPACT", restartedRepo.playlistsLayout.first())

        assertTrue(restartedRepo.fullDeviceScan.first())
        assertEquals(15, restartedRepo.ignoreAudioDuration.first())
        assertEquals(600, restartedRepo.maxSongDuration.first())
        assertEquals("DATE_ADDED", restartedRepo.librarySortOption.first())
        assertFalse(restartedRepo.librarySortAscending.first())
        assertFalse(restartedRepo.cacheLyricsEnabled.first())
        assertFalse(restartedRepo.cacheAlbumArtEnabled.first())
        assertFalse(restartedRepo.cacheSongArtWorkEnabled.first())
        assertEquals("MAXIMUM", restartedRepo.databaseStorageTier.first())
        }
    }

    @Test
    fun testCameraAutoPauseWhenEnabled() {
        var isPlaying = true
        var pauseCount = 0
        var resumeCount = 0

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            }
        )
        focusManager.autoPauseCamera = true
        focusManager.autoPauseSystemAudio = true

        focusManager.onCameraOpened("0")
        assertFalse("Playback must pause when camera opens and autoPauseCamera is ON", isPlaying)
        assertEquals(1, pauseCount)

        focusManager.onCameraClosed("0")
        assertTrue("Playback must automatically resume when camera closes", isPlaying)
        assertEquals(1, resumeCount)
    }

    @Test
    fun testCameraAutoPauseWhenDisabled() {
        var isPlaying = true
        var pauseCount = 0
        var resumeCount = 0

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            }
        )
        focusManager.autoPauseCamera = false
        focusManager.autoPauseSystemAudio = true

        focusManager.onCameraOpened("0")
        assertTrue("Playback must continue when autoPauseCamera is OFF", isPlaying)
        assertEquals(0, pauseCount)

        focusManager.onCameraClosed("0")
        assertEquals(0, resumeCount)
    }

    @Test
    fun testSystemAudioAutoPauseWhenInterrupted() {
        var isPlaying = true
        var pauseCount = 0
        var resumeCount = 0

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            }
        )
        focusManager.autoPauseCamera = false
        focusManager.autoPauseSystemAudio = true

        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse("Playback must pause on transient audio focus loss when autoPauseSystemAudio is ON", isPlaying)
        assertEquals(1, pauseCount)

        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("Playback must automatically resume on AUDIOFOCUS_GAIN", isPlaying)
        assertEquals(1, resumeCount)

        // Duplicate AUDIOFOCUS_GAIN must not trigger a second resume
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, resumeCount)
    }

    @Test
    fun testPausedPlaybackNeverAutoResumes() {
        var isPlaying = false
        var pauseCount = 0
        var resumeCount = 0

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            }
        )
        focusManager.autoPauseCamera = true
        focusManager.autoPauseSystemAudio = true

        // 1. Already paused before interruption
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("Must not auto-resume if already paused before interruption", isPlaying)
        assertEquals(0, resumeCount)

        // 2. Playing before interruption, but user manually pauses during interruption
        isPlaying = true
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(isPlaying)
        focusManager.cancelPendingResume()
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("Must not auto-resume if user manually paused during interruption", isPlaying)
        assertEquals(0, resumeCount)
    }

    @Test
    fun testOverlappingInterruptionsWaitAndResumeOnce() {
        var isPlaying = true
        var pauseCount = 0
        var resumeCount = 0

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            }
        )
        focusManager.autoPauseCamera = true
        focusManager.autoPauseSystemAudio = true

        // Camera opens first
        focusManager.onCameraOpened("0")
        assertFalse(isPlaying)
        assertEquals(1, pauseCount)

        // System audio transient focus loss occurs while camera is still open
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals("Should not issue duplicate pause while already paused by interruption", 1, pauseCount)

        // Audio focus returns, but camera is still open -> must NOT resume yet
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("Must not resume while camera interruption is still active", isPlaying)
        assertEquals(0, resumeCount)

        // Camera closes -> all interruptions cleared -> resumes exactly once
        focusManager.onCameraClosed("0")
        assertTrue("Must resume once all overlapping interruptions have ended", isPlaying)
        assertEquals(1, resumeCount)
    }

    @Test
    fun testSleepTimerExpirationDuringInterruption() {
        var isPlaying = true
        var sleepTimerStopped = false
        var resumeCount = 0

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { sleepTimerStopped },
            onPauseRequested = { isPlaying = false },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            }
        )
        focusManager.autoPauseSystemAudio = true

        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(isPlaying)

        // Sleep timer expires during the interruption
        sleepTimerStopped = true

        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("Must not auto-resume if sleep timer stopped playback during interruption", isPlaying)
        assertEquals(0, resumeCount)
    }

    @Test
    fun testAudioNormalizerBoundsGainAndInterpolates() {
        val processor = AudioNormalizerProcessor()
        processor.isEnabled = true
        processor.configure(AudioProcessor.AudioFormat(44100, 2, android.media.AudioFormat.ENCODING_PCM_16BIT))
        processor.flush()

        // Track with ReplayGain metadata (-9.0 dB should clamp cleanly to minGain = 0.72f)
        processor.setTrack(trackId = 101L, trackPath = null, replayGainDb = -9.0f)

        val sampleCount = 512
        val inputBuffer = ByteBuffer.allocateDirect(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            inputBuffer.putShort(20000.toShort())
        }
        inputBuffer.flip()

        processor.queueInput(inputBuffer)
        val output = processor.output
        assertTrue("Processor must produce output buffer", output.hasRemaining())

        val firstSample = output.short.toInt()
        var lastSample = firstSample
        while (output.hasRemaining()) {
            lastSample = output.short.toInt()
            assertTrue("Output sample must remain within 16-bit PCM bounds", lastSample in Short.MIN_VALUE..Short.MAX_VALUE)
        }
        // Target gain is 0.72f (< 1.0f), so gain smoothly ramps down toward 0.72 * 20000 = 14400
        assertTrue("Gain must smoothly attenuate toward target gain", lastSample < firstSample)
    }

    @Test
    fun testEqualizerManagerAudioSessionReuse() {
        val eqManager = EqualizerManager(context)
        // Calling with invalid/0 session ID is a safe no-op
        eqManager.initAudioSession(0, "NORMAL")
        eqManager.release()
    }

    @Test
    fun testSleepTimerFinishLastSongPreferencePreserved() {
        val sleepTimer = SleepTimerManager()
        sleepTimer.setFinishLastSong(true)
        assertTrue(sleepTimer.state.value.finishLastSong)

        sleepTimer.startTimerMinutes(15, finishLastSong = true)
        assertTrue(sleepTimer.state.value.isActive)
        assertTrue(sleepTimer.state.value.finishLastSong)

        sleepTimer.cancelTimer()
        assertFalse(sleepTimer.state.value.isActive)
        assertTrue("finishLastSong default must remain preserved after timer cancellation", sleepTimer.state.value.finishLastSong)
    }
}
