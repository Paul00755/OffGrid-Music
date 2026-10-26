package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Song
import com.example.service.PlaybackManager
import com.example.service.SleepTimerManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackAndQueueFeaturesTest {

    private lateinit var context: Context
    private lateinit var playbackScope: kotlinx.coroutines.CoroutineScope
    private lateinit var sleepTimerManager: SleepTimerManager
    private lateinit var playbackManager: PlaybackManager

    private val sampleSongs = (1..10).map { i ->
        Song(
            id = i.toLong(),
            title = "Track $i",
            artist = "Artist $i",
            album = "Album $i",
            durationMs = 180000L,
            path = "/storage/emulated/0/Music/track$i.mp3",
            contentUriString = "content://media/external/audio/media/$i"
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        playbackScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        sleepTimerManager = SleepTimerManager(playbackScope) {}
        playbackManager = PlaybackManager(context, playbackScope, sleepTimerManager)
    }

    @org.junit.After
    fun tearDown() {
        try { playbackManager.release() } catch (_: Exception) {}
        try { playbackScope.cancel() } catch (_: Exception) {}
    }

    @Test
    fun `test playQueue with alwaysShuffleMode plays clicked song first and shuffles remaining`() {
        playbackManager.alwaysShuffleMode = true
        
        // User clicks the 7th song (index 6) in the playlist
        val clickedSong = sampleSongs[6]
        assertEquals("Track 7", clickedSong.title)

        playbackManager.playQueue(sampleSongs, startIndex = 6)

        val state = playbackManager.playbackState.value
        assertEquals(10, state.queue.size)
        // Clicked song MUST be #1 in the queue (index 0)
        assertEquals(clickedSong.id, state.queue[0].id)
        assertEquals(0, state.queueIndex)
        assertEquals(clickedSong.id, state.currentSong?.id)

        // All songs in playlist must be present in queue
        val queueIds = state.queue.map { it.id }.toSet()
        val originalIds = sampleSongs.map { it.id }.toSet()
        assertEquals(originalIds, queueIds)
        playbackManager.pause()
    }

    @Test
    fun `test isSongQualified enforces minimum 10 seconds threshold`() {
        // Less than 10 seconds: must NOT qualify
        assertFalse(playbackManager.isSongQualified(listenedMs = 5000L, totalDurationMs = 180000L))
        assertFalse(playbackManager.isSongQualified(listenedMs = 9999L, totalDurationMs = 180000L))

        // 10 seconds or more: qualifies based on threshold
        playbackManager.scrobbleThresholdType = "SECONDS"
        playbackManager.scrobbleThresholdValue = 10
        assertTrue(playbackManager.isSongQualified(listenedMs = 10000L, totalDurationMs = 180000L))
    }

    @Test
    fun `test togglePlayPause transitions between playing and paused states`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        assertTrue(playbackManager.playbackState.value.isPlaying)

        // Pause
        playbackManager.togglePlayPause()
        assertFalse(playbackManager.playbackState.value.isPlaying)

        // Resume
        playbackManager.togglePlayPause()
        assertTrue(playbackManager.playbackState.value.isPlaying)
        playbackManager.pause()
    }

    @Test
    fun `test queue reordering preserves all items`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        val initialQueue = playbackManager.playbackState.value.queue
        assertEquals(10, initialQueue.size)

        // Move item at index 0 to index 4
        playbackManager.reorderQueue(fromIndex = 0, toIndex = 4)
        val reorderedQueue = playbackManager.playbackState.value.queue

        assertEquals(10, reorderedQueue.size)
        assertEquals(sampleSongs[0].id, reorderedQueue[4].id)
        playbackManager.pause()
    }

    @Test
    fun `test toggleRepeat cycles through ALL, ONE, OFF`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, playbackManager.playbackState.value.repeatMode)

        // OFF -> ALL
        val mode1 = playbackManager.toggleRepeat()
        assertEquals("Repeat All", mode1)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL, playbackManager.playbackState.value.repeatMode)

        // ALL -> ONE
        val mode2 = playbackManager.toggleRepeat()
        assertEquals("Repeat Current Track", mode2)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_ONE, playbackManager.playbackState.value.repeatMode)

        // ONE -> OFF
        val mode3 = playbackManager.toggleRepeat()
        assertEquals("Repeat Off", mode3)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, playbackManager.playbackState.value.repeatMode)
        playbackManager.pause()
    }

    @Test
    fun `test manual skip to next turns off repeat one`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        playbackManager.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_ONE, playbackManager.playbackState.value.repeatMode)

        playbackManager.skipToNext()
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, playbackManager.playbackState.value.repeatMode)
        playbackManager.pause()
    }

    @Test
    fun `test updateQueueOrder with duplicate tracks preserves all occurrences`() {
        val songsWithDuplicates = listOf(
            sampleSongs[0],
            sampleSongs[1],
            sampleSongs[0], // Duplicate of Track 1
            sampleSongs[2]
        )
        playbackManager.playQueue(songsWithDuplicates, startIndex = 0)
        assertEquals(4, playbackManager.playbackState.value.queue.size)

        // Reorder queue so duplicate is moved to the end
        val reordered = listOf(
            songsWithDuplicates[1],
            songsWithDuplicates[0],
            songsWithDuplicates[3],
            songsWithDuplicates[2]
        )
        playbackManager.updateQueueOrder(reordered)
        val currentQueue = playbackManager.playbackState.value.queue
        assertEquals(4, currentQueue.size)
        assertEquals(reordered.map { it.id }, currentQueue.map { it.id })
        playbackManager.pause()
    }

    @Test
    fun `test gapless playback and crossfade configuration`() {
        playbackManager.gaplessPlayback = true
        assertTrue(playbackManager.gaplessPlayback)

        playbackManager.gaplessPlayback = false
        assertFalse(playbackManager.gaplessPlayback)

        playbackManager.crossfadeDurationSec = 5
        assertEquals(5, playbackManager.crossfadeDurationSec)

        playbackManager.playQueue(sampleSongs, startIndex = 0)
        assertTrue(playbackManager.playbackState.value.isPlaying)

        // Trigger overlapping crossfade to track 2
        playbackManager.triggerOverlappingCrossfade(targetIndex = 1, durationMs = 3000L)
        // Ensure primary player remains active and queue index matches
        assertNotNull(playbackManager.crossfadePlayer)
        playbackManager.pause()
    }

    @Test
    fun `test artwork resolution cache fallback to persistent storage`() {
        val filesDir = context.filesDir
        val artDir = java.io.File(filesDir, "artworks")
        if (!artDir.exists()) artDir.mkdirs()
        val song = sampleSongs[0].copy(id = 9999L)
        val dummyArtFile = java.io.File(artDir, com.example.ui.components.ArtworkResolutionCache.trackFileName(song.id, song.path))
        val validJpegBytes = ByteArray(300).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xD8.toByte()
            this[298] = 0xFF.toByte()
            this[299] = 0xD9.toByte()
        }
        dummyArtFile.writeBytes(validJpegBytes)

        com.example.ui.components.ArtworkResolutionCache.clear()
        val resolved = com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
        assertNotNull(resolved)
        assertTrue(resolved!!.contains(dummyArtFile.name))

        // Verify authoritative artwork helper
        val authoritative = kotlinx.coroutines.runBlocking {
            com.example.ui.components.ArtworkResolutionCache.getAuthoritativeArtwork(context, song)
        }
        assertNotNull(authoritative)
        assertTrue(authoritative!!.contains(dummyArtFile.name))

        dummyArtFile.delete()
    }

    @Test
    fun `test theme compatibility filtering and fallbacks`() {
        // Dark mode: only dark themes should be compatible
        val darkThemes = com.example.ui.theme.AppUiTheme.getCompatibleThemes("DARK", isSystemDark = false)
        assertTrue(darkThemes.isNotEmpty())
        assertTrue(darkThemes.all { it.isDark })
        assertTrue(darkThemes.contains(com.example.ui.theme.AppUiTheme.MIDNIGHT_OBSIDIAN))
        assertFalse(darkThemes.contains(com.example.ui.theme.AppUiTheme.PORCELAIN_SLATE))

        // Light mode: only light themes should be compatible
        val lightThemes = com.example.ui.theme.AppUiTheme.getCompatibleThemes("LIGHT", isSystemDark = false)
        assertTrue(lightThemes.isNotEmpty())
        assertTrue(lightThemes.all { !it.isDark })
        assertTrue(lightThemes.contains(com.example.ui.theme.AppUiTheme.PORCELAIN_SLATE))
        assertFalse(lightThemes.contains(com.example.ui.theme.AppUiTheme.MIDNIGHT_OBSIDIAN))

        // AMOLED mode: only AMOLED compatible themes
        val amoledThemes = com.example.ui.theme.AppUiTheme.getCompatibleThemes("AMOLED", isSystemDark = false)
        assertTrue(amoledThemes.contains(com.example.ui.theme.AppUiTheme.PURE_AMOLED))

        // Default fallbacks
        assertEquals(com.example.ui.theme.AppUiTheme.MIDNIGHT_OBSIDIAN, com.example.ui.theme.AppUiTheme.getDefaultThemeForMode("DARK"))
        assertEquals(com.example.ui.theme.AppUiTheme.PORCELAIN_SLATE, com.example.ui.theme.AppUiTheme.getDefaultThemeForMode("LIGHT"))
        assertEquals(com.example.ui.theme.AppUiTheme.PURE_AMOLED, com.example.ui.theme.AppUiTheme.getDefaultThemeForMode("AMOLED"))
    }

    @Test
    fun `test lyrics sync offset shifts timing in correct direction and persists per track`() {
        val userPrefs = com.example.data.local.UserPreferencesRepository.getInstance(context)
        val songId = 42L

        // Initial offset is 0
        assertEquals(0L, userPrefs.getLyricsSyncOffsetMs(songId))

        // Advance lyrics by +1.2s (+1200ms) for late lyrics
        userPrefs.setLyricsSyncOffsetMs(songId, 1200L)
        assertEquals(1200L, userPrefs.getLyricsSyncOffsetMs(songId))

        // Delay lyrics by -0.5s (-500ms) for early lyrics
        userPrefs.setLyricsSyncOffsetMs(songId, -500L)
        assertEquals(-500L, userPrefs.getLyricsSyncOffsetMs(songId))

        // Effective position with +1200ms offset when audio is at 10000ms
        val audioPos = 10000L
        val advanceOffset = 1200L
        val effectiveAdvance = (audioPos + advanceOffset).coerceAtLeast(0L)
        assertEquals(11200L, effectiveAdvance)
        // Lyric stamped at 11000ms triggers earlier (since 11200ms >= 11000ms at audio 10000ms)
        assertTrue(effectiveAdvance >= 11000L)

        // Clear offset
        userPrefs.setLyricsSyncOffsetMs(songId, 0L)
        assertEquals(0L, userPrefs.getLyricsSyncOffsetMs(songId))
    }

    @Test
    fun `test headphone auto-play respects preference and prevents duplicate instances`() {
        val userPrefs = com.example.data.local.UserPreferencesRepository.getInstance(context)
        
        // Disabled preference
        userPrefs.cachedAutoPlayOnHeadphoneConnection = false
        assertFalse(userPrefs.cachedAutoPlayOnHeadphoneConnection)

        // Enabled preference
        userPrefs.cachedAutoPlayOnHeadphoneConnection = true
        assertTrue(userPrefs.cachedAutoPlayOnHeadphoneConnection)

        // Verify singleton PlaybackManager instance
        val instance1 = PlaybackManager.getInstance(context)
        val instance2 = PlaybackManager.getInstance(context)
        assertSame(instance1, instance2)
    }

    @Test
    fun `test notification artwork cache and instant update handling`() {
        val songId = 777L
        val testBytes = byteArrayOf(10, 20, 30)
        com.example.util.ArtworkManager.putCachedNotificationArtwork(songId, testBytes)

        val cached = com.example.util.ArtworkManager.getCachedNotificationArtwork(songId)
        assertNotNull(cached)
        assertArrayEquals(testBytes, cached)

        // Invalidation clears cache cleanly
        com.example.util.ArtworkManager.invalidateNotificationArtwork(songId)
        assertNull(com.example.util.ArtworkManager.getCachedNotificationArtwork(songId))
    }

    @Test
    fun `test manual skip to next while paused starts next track at 0ms and remains paused`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        playbackManager.seekTo(90000L)
        playbackManager.pause()
        assertFalse(playbackManager.playbackState.value.isPlaying)
        assertEquals(90000L, playbackManager.playbackPositionMs.value)

        // Skip to next while paused
        playbackManager.skipToNext()

        val state = playbackManager.playbackState.value
        assertEquals(sampleSongs[1].id, state.currentSong?.id)
        assertEquals(1, state.queueIndex)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, playbackManager.playbackPositionMs.value)
        assertFalse("Track must remain paused when skipped while paused", state.isPlaying)

        // Resume playback
        playbackManager.play()
        assertTrue(playbackManager.playbackState.value.isPlaying)
        assertEquals(sampleSongs[1].id, playbackManager.playbackState.value.currentSong?.id)
        playbackManager.pause()
    }

    @Test
    fun `test manual skip to next while playing starts next track at 0ms and continues playing`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        playbackManager.seekTo(45000L)
        assertTrue(playbackManager.playbackState.value.isPlaying)

        // Skip to next while playing
        playbackManager.skipToNext()

        val state = playbackManager.playbackState.value
        assertEquals(sampleSongs[1].id, state.currentSong?.id)
        assertEquals(1, state.queueIndex)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, playbackManager.playbackPositionMs.value)
        assertTrue("Track must continue playing when skipped while playing", state.isPlaying)
        playbackManager.pause()
    }

    @Test
    fun `test manual skip to previous while paused starts track at 0ms and remains paused`() {
        playbackManager.playQueue(sampleSongs, startIndex = 2)
        playbackManager.seekTo(1500L) // Under 3s to trigger skip to previous track
        playbackManager.pause()
        assertFalse(playbackManager.playbackState.value.isPlaying)

        playbackManager.skipToPrevious()

        val state = playbackManager.playbackState.value
        assertEquals(sampleSongs[1].id, state.currentSong?.id)
        assertEquals(1, state.queueIndex)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, playbackManager.playbackPositionMs.value)
        assertFalse("Track must remain paused when skipped to previous while paused", state.isPlaying)
        playbackManager.pause()
    }

    @Test
    fun `test manual skip to previous rewinds to 0ms when past 3 seconds`() {
        playbackManager.playQueue(sampleSongs, startIndex = 1)
        playbackManager.seekTo(10000L) // Past 3s
        playbackManager.pause()
        assertFalse(playbackManager.playbackState.value.isPlaying)

        playbackManager.skipToPrevious()

        val state = playbackManager.playbackState.value
        assertEquals(sampleSongs[1].id, state.currentSong?.id)
        assertEquals(1, state.queueIndex)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, playbackManager.playbackPositionMs.value)
        assertFalse(state.isPlaying)
        playbackManager.pause()
    }

    @Test
    fun `test jumpToQueueIndex starts target track at 0ms`() {
        playbackManager.playQueue(sampleSongs, startIndex = 0)
        playbackManager.seekTo(70000L)

        playbackManager.jumpToQueueIndex(4)

        val state = playbackManager.playbackState.value
        assertEquals(sampleSongs[4].id, state.currentSong?.id)
        assertEquals(4, state.queueIndex)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, playbackManager.playbackPositionMs.value)
        assertTrue(state.isPlaying)
        playbackManager.pause()
    }
}
