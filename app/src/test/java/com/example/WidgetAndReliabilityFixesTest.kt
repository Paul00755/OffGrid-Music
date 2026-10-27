package com.example

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.Song
import com.example.data.sync.BackupFrequency
import com.example.data.sync.CloudUserSettings
import com.example.service.AudioFocusManager
import com.example.service.PlaybackState
import com.example.widget.WidgetBitmapRenderer
import com.example.widget.WidgetUpdateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WidgetAndReliabilityFixesTest {

    @Test
    fun testWidgetLayoutsExposeSecondaryTapSeekTargets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inflater = LayoutInflater.from(context)

        val fullView = inflater.inflate(R.layout.widget_full_artwork, null)
        assertNotNull(fullView.findViewById<View>(R.id.widget_full_bg))
        assertNotNull(fullView.findViewById<View>(R.id.widget_full_btn_favorite))
        assertNotNull(fullView.findViewById<View>(R.id.widget_full_btn_play_pause))
        assertEquals(0, context.resources.getIdentifier("widget_full_progress", "id", context.packageName))
        assertEquals(0, context.resources.getIdentifier("widget_full_time_current", "id", context.packageName))

        val smallView = inflater.inflate(R.layout.widget_small, null)
        assertNotNull(smallView.findViewById<View>(R.id.widget_small_artwork))
        assertNotNull(smallView.findViewById<View>(R.id.widget_small_btn_favorite))
        assertNotNull(smallView.findViewById<View>(R.id.widget_small_btn_play_pause))
        assertEquals(0, context.resources.getIdentifier("widget_small_progress", "id", context.packageName))
        assertEquals(0, context.resources.getIdentifier("widget_small_time_current", "id", context.packageName))

        val mediumView = inflater.inflate(R.layout.widget_medium, null)
        assertNotNull(mediumView.findViewById<View>(R.id.widget_medium_progress))
        assertNotNull(mediumView.findViewById<View>(R.id.widget_medium_time_current))
        assertNotNull(mediumView.findViewById<View>(R.id.widget_medium_time_total))
        assertEquals(0, context.resources.getIdentifier("widget_medium_seek_0", "id", context.packageName))

        val secondaryView = inflater.inflate(R.layout.widget_secondary, null)
        assertNotNull(secondaryView.findViewById<View>(R.id.widget_secondary_progress))
        assertNotNull(secondaryView.findViewById<View>(R.id.widget_secondary_time_current))
        assertNotNull(secondaryView.findViewById<View>(R.id.widget_secondary_time_total))
        for (index in 0 until 10) {
            val zoneId = context.resources.getIdentifier("widget_secondary_seek_zone_$index", "id", context.packageName)
            assertTrue("Missing secondary seek target $index", zoneId != 0)
            assertNotNull(secondaryView.findViewById<View>(zoneId))
        }
    }

    @Test
    fun testAuthoritativeWidgetStatePreservesPositionAndDurationOnFavoriteToggle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val song = Song(
            id = 777L,
            title = "Test Track",
            artist = "Test Artist",
            album = "Test Album",
            albumId = 10L,
            durationMs = 240_000L,
            path = "/storage/emulated/0/Music/test.mp3",
            contentUriString = "content://media/external/audio/media/777",
            isFavorite = false
        )
        val initialState = PlaybackState(
            currentSong = song,
            isPlaying = true,
            positionMs = 95_400L,
            durationMs = 240_000L,
            queue = listOf(song),
            queueIndex = 0
        )

        // Save initial authoritative snapshot
        WidgetUpdateManager.savePlaybackSnapshot(context, initialState)

        // Simulate a stale candidate state where positionMs was accidentally 0L when toggling favorite
        val staleFavCandidate = initialState.copy(
            currentSong = song.copy(isFavorite = true),
            positionMs = 0L,
            durationMs = 0L
        )

        val resolved = WidgetUpdateManager.getAuthoritativeState(context, staleFavCandidate)
        assertNotNull(resolved.currentSong)
        assertTrue(resolved.currentSong!!.isFavorite)
        assertEquals(95_400L, resolved.positionMs)
        assertEquals(240_000L, resolved.durationMs)
    }

    @Test
    fun testWaveformProgressBarRendersVisibleTrackEvenAtZeroProgress() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bmp = WidgetBitmapRenderer.renderWaveformProgressBar(
            context = context,
            widthPx = 320,
            heightPx = 36,
            progressRatio = 0f,
            accentColor = 0xFFFF6D00.toInt()
        )
        assertNotNull(bmp)
        assertEquals(320, bmp.width)
        assertEquals(36, bmp.height)
    }

    @Test
    fun testAutomaticBackupAndFrequencyPersistAcrossEqualOrOlderCloudSync() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository.getInstance(context)

        prefs.setAutomaticBackupEnabled(true)
        prefs.setBackupFrequency(BackupFrequency.DAILY)

        assertTrue(prefs.automaticBackupEnabled.first())
        assertEquals(BackupFrequency.DAILY, prefs.backupFrequency.first())
        val localUpdated = prefs.settingsLastUpdatedAt.first()
        assertTrue("Expected settingsLastUpdatedAt > 0 after backup preference update", localUpdated > 0L)

        // Simulate a cloud settings snapshot with an older or equal updatedAt that has default WEEKLY / false
        val staleCloudSettings = CloudUserSettings(
            automaticBackupEnabled = false,
            backupFrequency = "WEEKLY",
            updatedAt = localUpdated
        )
        val applied = prefs.importUserSettings(staleCloudSettings, checkTimestamp = true)
        assertFalse("Stale or equal-timestamp cloud settings must not overwrite local settings", applied)
        assertTrue(prefs.automaticBackupEnabled.first())
        assertEquals(BackupFrequency.DAILY, prefs.backupFrequency.first())
    }

    @Test
    fun testRepeatedBackgroundToForegroundCyclesPreservePlaybackAndSyncPositionImmediately() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository.getInstance(context)
        val prevInstance = com.example.service.PlaybackManager.activeInstance
        val pm = com.example.service.PlaybackManager.getInstance(context)
        try {
            val songA = Song(
                id = 901L,
                title = "Background Smoothness A",
                artist = "OffGrid Artist",
                album = "OffGrid Album",
                albumId = 21L,
                durationMs = 210_000L,
                path = "/storage/emulated/0/Music/bg_a.mp3",
                contentUriString = "content://media/external/audio/media/901"
            )
            val songB = Song(
                id = 902L,
                title = "Background Smoothness B",
                artist = "OffGrid Artist",
                album = "OffGrid Album",
                albumId = 21L,
                durationMs = 195_000L,
                path = "/storage/emulated/0/Music/bg_b.mp3",
                contentUriString = "content://media/external/audio/media/902"
            )

            pm.restorePlaybackState(listOf(songA, songB), startIndex = 0, startPositionMs = 45_000L, fallbackDurationMs = 210_000L)
            val initialPlayerRef = pm.player

            // Test across both High Refresh Rate enabled (90Hz/120Hz) and disabled (60Hz)
            for (highRefresh in listOf(true, false)) {
                prefs.setHighRefreshRate(highRefresh)
                assertEquals(highRefresh, prefs.highRefreshRate.first())

                // Run 4 repeated background -> foreground cycles
                for (cycle in 1..4) {
                    // 1. App goes to background while music is active
                    pm.onAppForegroundStateChanged(false)
                    assertFalse("Expected isUiInForeground == false when backgrounded (cycle $cycle)", pm.isUiInForeground)

                    // Simulate position advancing while backgrounded
                    val simulatedBgPos = 45_000L + (cycle * 15_000L)
                    pm.seekTo(simulatedBgPos)
                    WidgetUpdateManager.updateWidgetsProgress(context, simulatedBgPos, 210_000L)

                    // 2. App returns to foreground
                    val resumeStartNs = System.nanoTime()
                    pm.onAppForegroundStateChanged(true)
                    val resumeElapsedMs = (System.nanoTime() - resumeStartNs) / 1_000_000.0

                    assertTrue("Expected isUiInForeground == true on resume (cycle $cycle)", pm.isUiInForeground)
                    assertSame("Media3 ExoPlayer instance must be reused across background/foreground cycles", initialPlayerRef, pm.player)
                    assertEquals("Current song must be preserved on resume", songA.id, pm.playbackState.value.currentSong?.id)
                    assertEquals("Position must be immediately synchronized on resume", simulatedBgPos, pm.playbackPositionMs.value)
                    assertEquals("PlaybackState.positionMs must be immediately synchronized on resume", simulatedBgPos, pm.playbackState.value.positionMs)
                    assertEquals("Duration must be preserved on resume", 210_000L, pm.playbackState.value.durationMs)
                    assertTrue("Foreground sync latency should be under 50ms (was ${resumeElapsedMs}ms)", resumeElapsedMs < 50.0)
                }
            }
        } finally {
            com.example.service.PlaybackManager.activeInstance = prevInstance
        }
    }

    @Test
    fun artworkCacheSeparatesTracksThatShareRoomIdentity() {
        val cache = com.example.ui.components.ArtworkResolutionCache
        val songId = 98_765_432L
        val pathA = "/music/album-a/track.mp3"
        val pathB = "/music/album-b/track.mp3"
        val uriA = "file:///cache/track-a.jpg"
        val uriB = "file:///cache/track-b.jpg"

        cache.invalidateTrack(songId, pathA)
        cache.invalidateTrack(songId, pathB)
        cache.cacheArtwork(songId, pathA, uriA)
        assertEquals(uriA, cache.getMemory(songId, pathA))
        assertNull("A different file must not inherit the first track's artwork", cache.getMemory(songId, pathB))

        cache.cacheArtwork(songId, pathB, uriB)
        assertEquals(uriA, cache.getMemory(songId, pathA))
        assertEquals(uriB, cache.getMemory(songId, pathB))

        val bitmapA = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val bitmapB = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val bytesA = byteArrayOf(1, 2, 3)
        val bytesB = byteArrayOf(4, 5, 6)
        val artworkManager = com.example.util.ArtworkManager
        try {
            artworkManager.putCachedNotificationBitmap(songId, bitmapA, pathA)
            artworkManager.putCachedNotificationArtwork(songId, bytesA, pathA)
            assertSame(bitmapA, artworkManager.getCachedNotificationBitmap(songId, pathA))
            assertArrayEquals(bytesA, artworkManager.getCachedNotificationArtwork(songId, pathA))
            assertNull("Notification artwork must not cross paths for a reused Room ID", artworkManager.getCachedNotificationBitmap(songId, pathB))
            assertNull(artworkManager.getCachedNotificationArtwork(songId, pathB))

            artworkManager.putCachedNotificationBitmap(songId, bitmapB, pathB)
            artworkManager.putCachedNotificationArtwork(songId, bytesB, pathB)
            assertSame(bitmapA, artworkManager.getCachedNotificationBitmap(songId, pathA))
            assertSame(bitmapB, artworkManager.getCachedNotificationBitmap(songId, pathB))
            assertArrayEquals(bytesA, artworkManager.getCachedNotificationArtwork(songId, pathA))
            assertArrayEquals(bytesB, artworkManager.getCachedNotificationArtwork(songId, pathB))
            artworkManager.invalidateNotificationArtwork(songId, pathA)
            assertNull(artworkManager.getCachedNotificationBitmap(songId, pathA))
            assertNull(artworkManager.getCachedNotificationArtwork(songId, pathA))
            assertSame("Invalidating one file must retain the other file's cover", bitmapB, artworkManager.getCachedNotificationBitmap(songId, pathB))
            assertArrayEquals(bytesB, artworkManager.getCachedNotificationArtwork(songId, pathB))
        } finally {
            artworkManager.invalidateNotificationArtwork(songId)
            bitmapA.recycle()
            bitmapB.recycle()
        }
        cache.invalidateTrack(songId, pathA)
        cache.invalidateTrack(songId, pathB)
    }

    @Test
    fun overlappingAudioFocusLossResumesOnceAndManualPauseCancelsResume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun listenerFor(manager: AudioFocusManager): android.media.AudioManager.OnAudioFocusChangeListener {
            val field = AudioFocusManager::class.java.getDeclaredField("focusChangeListener")
            field.isAccessible = true
            return field.get(manager) as android.media.AudioManager.OnAudioFocusChangeListener
        }

        var playing = true
        var pauseCount = 0
        var resumeCount = 0
        val manager = AudioFocusManager(
            context = context,
            isPlayingProvider = { playing },
            onPauseRequested = { pauseCount++; playing = false },
            onResumeRequested = { resumeCount++; playing = true }
        )
        val listener = listenerFor(manager)
        listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertEquals("Overlapping focus-loss callbacks pause only once", 1, pauseCount)
        assertEquals("One focus gain resumes the interrupted playback once", 1, resumeCount)

        playing = true
        val manuallyPausedManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { playing },
            onPauseRequested = { playing = false },
            onResumeRequested = { resumeCount++ }
        )
        val manualPauseListener = listenerFor(manuallyPausedManager)
        manualPauseListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        manuallyPausedManager.cancelPendingResume()
        manualPauseListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertEquals("An explicit pause prevents automatic resume", 1, resumeCount)
    }
}
