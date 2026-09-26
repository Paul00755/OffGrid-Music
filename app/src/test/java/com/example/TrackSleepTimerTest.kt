package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Song
import com.example.service.PlaybackManager
import com.example.service.SleepTimerManager
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
class TrackSleepTimerTest {

    private lateinit var context: Context
    private lateinit var testScope: TestScope
    private lateinit var sleepTimerManager: SleepTimerManager
    private lateinit var playbackManager: PlaybackManager

    private val sampleSongs = (1..10).map { i ->
        Song(
            id = i.toLong(),
            title = "Song ${('A'.code + i - 1).toChar()}", // Song A, Song B, etc.
            artist = "Artist $i",
            album = "Album $i",
            durationMs = 226000L, // 3:46
            path = "/storage/emulated/0/Music/track$i.mp3",
            contentUriString = "content://media/external/audio/media/$i"
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        sleepTimerManager = SleepTimerManager(testScope) {}
        playbackManager = PlaybackManager(context, testScope, sleepTimerManager)
    }

    @org.junit.After
    fun tearDown() {
        try { playbackManager.release() } catch (_: Exception) {}
    }

    @Test
    fun `test exact acceptance scenario - Song A ignored, 5 tracks counted, F finishes, stops at G 0 00`() {
        // Currently playing -> Song A (id = 1L)
        val songA = sampleSongs[0]
        assertEquals("Song A", songA.title)

        // User enables Track Sleep Timer = 5 tracks
        sleepTimerManager.startSongCount(5, currentSongId = songA.id)

        var state = sleepTimerManager.state.value
        assertTrue(state.isActive)
        assertEquals("SONGS", state.mode)
        assertEquals(5, state.totalSongs)
        assertEquals(5, state.remainingSongs)
        assertEquals(0, state.elapsedSongs)
        assertEquals(songA.id, state.initialSongId)
        assertFalse(state.isFinalSleepTrack)

        // 1. Song A finishes naturally -> Song B starts:
        // Expected: "A = ignored", "B = 1/5"
        val songB = sampleSongs[1]
        var shouldStop = sleepTimerManager.onTrackTransition(
            oldTrackId = songA.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songB.id
        )
        assertFalse("Playback must not stop when Song B starts", shouldStop)
        state = sleepTimerManager.state.value
        assertEquals(1, state.elapsedSongs)
        assertEquals(5, state.totalSongs)
        assertEquals(5, state.remainingSongs)
        assertFalse(state.isFinalSleepTrack)

        // 2. Song B finishes naturally -> Song C starts:
        // Expected: "C = 2/5"
        val songC = sampleSongs[2]
        shouldStop = sleepTimerManager.onTrackTransition(
            oldTrackId = songB.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songC.id
        )
        assertFalse("Playback must not stop when Song C starts", shouldStop)
        state = sleepTimerManager.state.value
        assertEquals(2, state.elapsedSongs)
        assertEquals(5, state.totalSongs)
        assertEquals(4, state.remainingSongs)
        assertFalse(state.isFinalSleepTrack)

        // 3. Song C finishes naturally -> Song D starts:
        // Expected: "D = 3/5"
        val songD = sampleSongs[3]
        shouldStop = sleepTimerManager.onTrackTransition(
            oldTrackId = songC.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songD.id
        )
        assertFalse("Playback must not stop when Song D starts", shouldStop)
        state = sleepTimerManager.state.value
        assertEquals(3, state.elapsedSongs)
        assertEquals(5, state.totalSongs)
        assertEquals(3, state.remainingSongs)
        assertFalse(state.isFinalSleepTrack)

        // 4. Song D finishes naturally -> Song E starts:
        // Expected: "E = 4/5"
        val songE = sampleSongs[4]
        shouldStop = sleepTimerManager.onTrackTransition(
            oldTrackId = songD.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songE.id
        )
        assertFalse("Playback must not stop when Song E starts", shouldStop)
        state = sleepTimerManager.state.value
        assertEquals(4, state.elapsedSongs)
        assertEquals(5, state.totalSongs)
        assertEquals(2, state.remainingSongs)
        assertFalse(state.isFinalSleepTrack)

        // 5. Song E finishes naturally -> Song F starts:
        // Expected: "F = 5/5 -> final track -> continue playing"
        val songF = sampleSongs[5]
        shouldStop = sleepTimerManager.onTrackTransition(
            oldTrackId = songE.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songF.id
        )
        assertFalse("Playback must NOT stop when final track starts", shouldStop)
        state = sleepTimerManager.state.value
        assertEquals(5, state.elapsedSongs)
        assertEquals(5, state.totalSongs)
        assertEquals(1, state.remainingSongs)
        assertTrue("Song F must be marked as final track", state.isFinalSleepTrack)

        // 6. Song F plays completely until "3:46 / 3:46" -> actual completion -> Transit to Song G Screen -> STOP
        val songG = sampleSongs[6]
        assertEquals("Song G", songG.title)
        shouldStop = sleepTimerManager.onTrackTransition(
            oldTrackId = songF.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songG.id
        )
        assertTrue("Must signal stop on Song G after Song F completion", shouldStop)
        // Timer should now be reset/cancelled
        assertFalse(sleepTimerManager.state.value.isActive)
    }

    @Test
    fun `test skipped and ineligible tracks do not consume the counter`() {
        // Start playing Song A
        val songA = sampleSongs[0]
        sleepTimerManager.startSongCount(3, currentSongId = songA.id)

        // Song A ends -> Song B starts (Track 1 / 3)
        val songB = sampleSongs[1]
        sleepTimerManager.onTrackTransition(
            oldTrackId = songA.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songB.id
        )
        assertEquals(1, sleepTimerManager.state.value.elapsedSongs)

        // User skips Song B after 2 seconds (ineligible: isOldQualified = false, oldNaturalEnd = false)
        val songC = sampleSongs[2]
        val shouldStop1 = sleepTimerManager.onTrackTransition(
            oldTrackId = songB.id,
            isOldQualified = false,
            oldNaturalEnd = false,
            newTrackId = songC.id
        )
        assertFalse(shouldStop1)
        // Counter must NOT have consumed a count! Song C becomes Track 1!
        assertEquals(1, sleepTimerManager.state.value.elapsedSongs)
        assertEquals(3, sleepTimerManager.state.value.remainingSongs)
        assertFalse(sleepTimerManager.state.value.isFinalSleepTrack)

        // Song C plays to natural end -> Song D starts (Track 2 / 3)
        val songD = sampleSongs[3]
        sleepTimerManager.onTrackTransition(
            oldTrackId = songC.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songD.id
        )
        assertEquals(2, sleepTimerManager.state.value.elapsedSongs)

        // User skips Song D after 1 second (ineligible)
        val songE = sampleSongs[4]
        sleepTimerManager.onTrackTransition(
            oldTrackId = songD.id,
            isOldQualified = false,
            oldNaturalEnd = false,
            newTrackId = songE.id
        )
        // Song D did not consume count! Song E is Track 2!
        assertEquals(2, sleepTimerManager.state.value.elapsedSongs)
        assertEquals(2, sleepTimerManager.state.value.remainingSongs)
        assertFalse(sleepTimerManager.state.value.isFinalSleepTrack)

        // Song E plays to natural end -> Song F starts (Track 3 / 3 - final track)
        val songF = sampleSongs[5]
        sleepTimerManager.onTrackTransition(
            oldTrackId = songE.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songF.id
        )
        assertEquals(3, sleepTimerManager.state.value.elapsedSongs)
        assertTrue(sleepTimerManager.state.value.isFinalSleepTrack)

        // Song F finishes naturally -> transitions to Song G -> STOPS
        val songG = sampleSongs[6]
        val shouldStopFinal = sleepTimerManager.onTrackTransition(
            oldTrackId = songF.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songG.id
        )
        assertTrue(shouldStopFinal)
        assertFalse(sleepTimerManager.state.value.isActive)
    }

    @Test
    fun `test skipping final track before qualification does not stop playback prematurely`() {
        val songA = sampleSongs[0]
        sleepTimerManager.startSongCount(1, currentSongId = songA.id)

        // Song A ends -> Song B starts (Track 1 / 1 -> final track)
        val songB = sampleSongs[1]
        sleepTimerManager.onTrackTransition(
            oldTrackId = songA.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songB.id
        )
        assertTrue(sleepTimerManager.state.value.isFinalSleepTrack)

        // User skips Song B after 2 seconds (ineligible) -> Song C starts
        val songC = sampleSongs[2]
        val shouldStop = sleepTimerManager.onTrackTransition(
            oldTrackId = songB.id,
            isOldQualified = false,
            oldNaturalEnd = false,
            newTrackId = songC.id
        )
        assertFalse("Playback must not stop on ineligible skip of final track", shouldStop)
        // Song C is now the final track candidate
        assertTrue(sleepTimerManager.state.value.isFinalSleepTrack)

        // Song C finishes naturally -> transitions to Song D -> STOPS
        val songD = sampleSongs[3]
        val shouldStopFinal = sleepTimerManager.onTrackTransition(
            oldTrackId = songC.id,
            isOldQualified = true,
            oldNaturalEnd = true,
            newTrackId = songD.id
        )
        assertTrue(shouldStopFinal)
        assertFalse(sleepTimerManager.state.value.isActive)
    }

    @Test
    fun `test cancel and reconfiguration resets track counter completely`() {
        sleepTimerManager.startSongCount(5, currentSongId = 1L)
        assertTrue(sleepTimerManager.state.value.isActive)
        assertEquals(5, sleepTimerManager.state.value.totalSongs)

        // Cancel
        sleepTimerManager.cancelTimer()
        assertFalse(sleepTimerManager.state.value.isActive)
        assertEquals(0, sleepTimerManager.state.value.totalSongs)
        assertEquals(0, sleepTimerManager.state.value.remainingSongs)

        // Reconfigure to 2 tracks
        sleepTimerManager.startSongCount(2, currentSongId = 2L)
        assertTrue(sleepTimerManager.state.value.isActive)
        assertEquals(2, sleepTimerManager.state.value.totalSongs)
        assertEquals(2, sleepTimerManager.state.value.remainingSongs)
        assertEquals(0, sleepTimerManager.state.value.elapsedSongs)
        assertFalse(sleepTimerManager.state.value.isFinalSleepTrack)
    }
}
