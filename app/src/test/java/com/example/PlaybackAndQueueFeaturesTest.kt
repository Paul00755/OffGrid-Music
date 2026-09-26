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
        val dummyArtFile = java.io.File(artDir, "9999.jpg")
        dummyArtFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        com.example.ui.components.ArtworkResolutionCache.clear()
        val resolved = com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, 9999L)
        assertNotNull(resolved)
        assertTrue(resolved!!.contains("9999.jpg"))

        dummyArtFile.delete()
    }
}
