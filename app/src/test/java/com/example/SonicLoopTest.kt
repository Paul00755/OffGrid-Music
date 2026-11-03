package com.example

import com.example.util.AbLoopState
import com.example.util.SonicStudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SonicLoopTest {

    @Before
    fun setUp() {
        SonicStudioEngine.setLoopCount(AbLoopState.DEFAULT_LOOP_COUNT)
        SonicStudioEngine.clearAbLoop()
    }

    @Test
    fun defaultLoopCountIs3AndMaxLoopCountIsClampedTo15() {
        val initial = SonicStudioEngine.abLoopState.value
        assertEquals(3, AbLoopState.DEFAULT_LOOP_COUNT)
        assertEquals(15, AbLoopState.MAX_LOOP_COUNT)
        assertEquals(3, initial.maxLoopCount)

        SonicStudioEngine.setLoopCount(99)
        assertEquals(15, SonicStudioEngine.abLoopState.value.maxLoopCount)

        SonicStudioEngine.setLoopCount(0)
        assertEquals(1, SonicStudioEngine.abLoopState.value.maxLoopCount)
    }

    @Test
    fun manualLoopPointsClampToSongTotalDuration() {
        val songId = 42L
        val songDurationMs = 180_000L // 3:00

        val parsedA = SonicStudioEngine.parseTimestampToMs("0:30")
        val parsedB = SonicStudioEngine.parseTimestampToMs("4:45") // Exceeds 3:00 duration!
        assertNotNull(parsedA)
        assertNotNull(parsedB)

        val res = SonicStudioEngine.setManualLoopPoints(
            songId = songId,
            rawStartMs = parsedA,
            rawEndMs = parsedB,
            durationMs = songDurationMs,
            loopCount = 5
        )
        assertTrue(res.success)
        assertTrue(res.wasClampedToDuration)
        assertEquals(30_000L, res.state.startMs)
        assertEquals(180_000L, res.state.endMs) // Clamped to song's total duration (3:00)
        assertEquals(5, res.state.maxLoopCount)
        assertTrue(res.state.isActive)
    }

    @Test
    fun loopContinuesPlayingNormallyAfterUserSetLoopCountFinishes() {
        val songId = 100L
        val durationMs = 240_000L
        // Configure Point A = 10s, Point B = 20s, default 3 loops
        val result = SonicStudioEngine.setManualLoopPoints(
            songId = songId,
            rawStartMs = 10_000L,
            rawEndMs = 20_000L,
            durationMs = durationMs,
            loopCount = 3
        )
        assertTrue(result.success)
        assertEquals(3, SonicStudioEngine.abLoopState.value.maxLoopCount)
        assertEquals(0, SonicStudioEngine.abLoopState.value.completedLoops)

        // Loop 1: reaches Point B (20_000L) -> seeks back to Point A (10_000L)
        val seek1 = SonicStudioEngine.checkAndConsumeLoopTrigger(songId, 20_100L)
        assertEquals(10_000L, seek1)
        assertEquals(1, SonicStudioEngine.abLoopState.value.completedLoops)
        assertTrue(SonicStudioEngine.abLoopState.value.isActive)

        // Loop 2: reaches Point B (20_000L) -> seeks back to Point A (10_000L)
        val seek2 = SonicStudioEngine.checkAndConsumeLoopTrigger(songId, 20_050L)
        assertEquals(10_000L, seek2)
        assertEquals(2, SonicStudioEngine.abLoopState.value.completedLoops)
        assertTrue(SonicStudioEngine.abLoopState.value.isActive)

        // Loop 3: reaches Point B (20_000L) -> seeks back to Point A (10_000L)
        val seek3 = SonicStudioEngine.checkAndConsumeLoopTrigger(songId, 20_000L)
        assertEquals(10_000L, seek3)
        assertEquals(3, SonicStudioEngine.abLoopState.value.completedLoops)
        assertTrue(SonicStudioEngine.abLoopState.value.isActive)

        // After 3rd loop finishes at Point B: returns null and deactivates loop so playback continues normally!
        val seekAfterCompletion = SonicStudioEngine.checkAndConsumeLoopTrigger(songId, 20_200L)
        assertNull(seekAfterCompletion)
        assertFalse(SonicStudioEngine.abLoopState.value.isActive)
        assertTrue(SonicStudioEngine.abLoopState.value.isLoopCountExhausted)
    }
}
