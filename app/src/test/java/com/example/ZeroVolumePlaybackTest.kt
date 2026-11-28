package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.Song
import com.example.service.PlaybackManager
import com.example.service.SleepTimerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZeroVolumePlaybackTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var playbackScope: CoroutineScope
    private lateinit var sleepTimerManager: SleepTimerManager
    private lateinit var playbackManager: PlaybackManager

    private val sampleSongs = listOf(
        Song(
            id = 1L,
            title = "Track One",
            artist = "Artist One",
            album = "Album One",
            durationMs = 210_000L,
            path = "/storage/emulated/0/Music/track1.mp3",
            contentUriString = "content://media/external/audio/media/1"
        ),
        Song(
            id = 2L,
            title = "Track Two",
            artist = "Artist Two",
            album = "Album Two",
            durationMs = 195_000L,
            path = "/storage/emulated/0/Music/track2.mp3",
            contentUriString = "content://media/external/audio/media/2"
        )
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        UserPreferencesRepository.resetInstanceForTesting(context)
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0)
        playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        sleepTimerManager = SleepTimerManager(playbackScope) {}
        playbackManager = PlaybackManager(context, playbackScope, sleepTimerManager)
        playbackManager.fadeInMs = 0
        playbackManager.fadeOutMs = 0
        playbackManager.pauseOnZeroVolume = true
        playbackManager.autoResumeOnVolumeIncrease = true
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        try { playbackManager.release() } catch (_: Exception) {}
        try { playbackScope.cancel() } catch (_: Exception) {}
        UserPreferencesRepository.resetInstanceForTesting(context)
    }

    private fun pumpMainUntil(timeoutMs: Long = 2500L, condition: () -> Boolean) {
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        var elapsed = 0L
        while (elapsed <= timeoutMs) {
            mainLooper.idle()
            if (condition()) return
            mainLooper.idleFor(50L, TimeUnit.MILLISECONDS)
            elapsed += 50L
            Thread.sleep(10L)
        }
        mainLooper.idle()
    }

    private fun sendVolumeBroadcast(value: Int) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        context.sendBroadcast(
            Intent("android.media.VOLUME_CHANGED_ACTION").apply {
                putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
                putExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", value)
            }
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sendMuteBroadcast(muted: Boolean) {
        context.sendBroadcast(
            Intent("android.media.STREAM_MUTE_CHANGED_ACTION").apply {
                putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
                putExtra("android.media.EXTRA_STREAM_VOLUME_MUTED", muted)
            }
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `rapid drop to zero pauses and increasing volume auto-resumes via broadcast`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Playback should start playing", playbackManager.playbackState.value.isPlaying)

        // Rapid slider/button drops well inside 60ms must NOT drop the final volume=0 event
        sendVolumeBroadcast(2)
        sendVolumeBroadcast(1)
        sendVolumeBroadcast(0)

        pumpMainUntil(1500L) {
            playbackManager.wasPausedByZeroVolume && !playbackManager.playbackState.value.isPlaying
        }
        assertFalse("Playback must pause when volume drops to 0", playbackManager.playbackState.value.isPlaying)
        assertTrue("wasPausedByZeroVolume must be flagged", playbackManager.wasPausedByZeroVolume)

        // Rapidly increase volume back up from 0 -> 1 -> 2
        sendVolumeBroadcast(1)
        sendVolumeBroadcast(2)
        pumpMainUntil(1500L) { playbackManager.playbackState.value.isPlaying }

        assertTrue("Playback must auto-resume when volume increases above zero", playbackManager.playbackState.value.isPlaying)
        assertFalse("wasPausedByZeroVolume must clear after auto-resume", playbackManager.wasPausedByZeroVolume)
    }

    @Test
    fun `content observer and monitor poll handle missed volume broadcasts in both directions`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(playbackManager.playbackState.value.isPlaying)

        // Simulate OS ContentObserver firing on Settings.System without VOLUME_CHANGED_ACTION
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        context.contentResolver.notifyChange(Settings.System.CONTENT_URI, null)
        pumpMainUntil(1500L) {
            playbackManager.wasPausedByZeroVolume && !playbackManager.playbackState.value.isPlaying
        }
        assertFalse("ContentObserver change to 0 volume must pause playback even at pos=0", playbackManager.playbackState.value.isPlaying)
        assertTrue(playbackManager.wasPausedByZeroVolume)

        // Simulate ROM suppressing broadcast + ContentObserver on volume raise: monitor job still auto-resumes
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        pumpMainUntil(2500L) { playbackManager.playbackState.value.isPlaying }

        assertTrue("Resume monitor must auto-resume when STREAM_MUSIC increases above zero", playbackManager.playbackState.value.isPlaying)
        assertFalse(playbackManager.wasPausedByZeroVolume)
    }

    @Test
    fun `stream mute broadcast pauses playback and unmuting resumes`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        sendMuteBroadcast(muted = true)
        pumpMainUntil(1500L) {
            playbackManager.wasPausedByZeroVolume && !playbackManager.playbackState.value.isPlaying
        }
        assertFalse("Mute action must pause playback", playbackManager.playbackState.value.isPlaying)
        assertTrue(playbackManager.wasPausedByZeroVolume)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)
        sendMuteBroadcast(muted = false)
        pumpMainUntil(1500L) { playbackManager.playbackState.value.isPlaying }

        assertTrue("Unmuting with non-zero volume must auto-resume playback", playbackManager.playbackState.value.isPlaying)
        assertFalse(playbackManager.wasPausedByZeroVolume)
    }

    @Test
    fun `manual pause clears zero-volume flag and disabled toggles are respected`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Drop to 0 -> paused by zero volume
        sendVolumeBroadcast(0)
        pumpMainUntil(1500L) { playbackManager.wasPausedByZeroVolume }

        // User explicitly pauses manually -> raising volume later must NOT resume
        playbackManager.pause()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(playbackManager.wasPausedByZeroVolume)

        sendVolumeBroadcast(5)
        Thread.sleep(150L)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Manual pause must prevent auto-resume on volume increase", playbackManager.playbackState.value.isPlaying)

        // Disable autoResumeOnVolumeIncrease -> remains paused after zero-volume pause
        kotlinx.coroutines.runBlocking {
            UserPreferencesRepository.getInstance(context).setAutoResumeOnVolumeIncrease(false)
        }
        playbackManager.autoResumeOnVolumeIncrease = false
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        playbackManager.play()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sendVolumeBroadcast(0)
        pumpMainUntil(1500L) { playbackManager.wasPausedByZeroVolume }

        sendVolumeBroadcast(5)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500L, TimeUnit.MILLISECONDS)
        assertFalse("Disabled autoResumeOnVolumeIncrease must keep playback paused", playbackManager.playbackState.value.isPlaying)
    }

    @Test
    fun `raising volume during fade-out reversal does not lose auto-resume state`() {
        playbackManager.fadeInMs = 300
        playbackManager.fadeOutMs = 500
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Simulate a zero-volume pause with fade-out in progress
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        playbackManager.pauseWithFade(customFadeOutMs = 500, fromZeroVolume = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(playbackManager.wasPausedByZeroVolume)

        // Immediately raise volume back above zero even while fade-out state was active
        sendVolumeBroadcast(4)
        pumpMainUntil(1500L) { playbackManager.playbackState.value.isPlaying }
        assertTrue("Raising volume during zero-volume fade must auto-resume", playbackManager.playbackState.value.isPlaying)
        assertFalse(playbackManager.wasPausedByZeroVolume)
    }
}
