package com.example

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.Song
import com.example.data.sync.BackupFrequency
import com.example.data.sync.CloudUserSettings
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
    fun testFullArtworkAndSmallWidgetsHaveNoSeekBarWhileMediumAndSecondaryHaveSeekSegments() {
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
        assertNotNull(mediumView.findViewById<View>(R.id.widget_medium_seek_0))
        assertNotNull(mediumView.findViewById<View>(R.id.widget_medium_seek_9))

        val secondaryView = inflater.inflate(R.layout.widget_secondary, null)
        assertNotNull(secondaryView.findViewById<View>(R.id.widget_secondary_progress))
        assertNotNull(secondaryView.findViewById<View>(R.id.widget_secondary_seek_0))
        assertNotNull(secondaryView.findViewById<View>(R.id.widget_secondary_seek_9))
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
}
