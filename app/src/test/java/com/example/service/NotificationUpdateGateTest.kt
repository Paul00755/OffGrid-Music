package com.example.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationUpdateGateTest {

    @Test
    fun playingProgressUpdatesAtLeastOncePerSecond() {
        val gate = NotificationUpdateGate()
        val key = notificationKey()

        assertTrue(gate.shouldUpdate(key, positionMs = 0L, isPlaying = true, nowMs = 1_000L))
        assertFalse(gate.shouldUpdate(key, positionMs = 200L, isPlaying = true, nowMs = 1_200L))
        assertFalse(gate.shouldUpdate(key, positionMs = 900L, isPlaying = true, nowMs = 1_999L))
        assertTrue(gate.shouldUpdate(key, positionMs = 1_000L, isPlaying = true, nowMs = 2_000L))
    }

    @Test
    fun playbackStateChangesUpdateImmediately() {
        val gate = NotificationUpdateGate()
        val playing = notificationKey(isPlaying = true, playWhenReady = true)
        val paused = notificationKey(isPlaying = false, playWhenReady = false)

        assertTrue(gate.shouldUpdate(playing, positionMs = 2_000L, isPlaying = true, nowMs = 1_000L))
        assertTrue(gate.shouldUpdate(paused, positionMs = 2_000L, isPlaying = false, nowMs = 1_100L))
        assertFalse(gate.shouldUpdate(paused, positionMs = 2_000L, isPlaying = false, nowMs = 1_200L))
    }

    @Test
    fun seeksUpdateImmediatelyEvenWhenProgressIntervalHasNotElapsed() {
        val gate = NotificationUpdateGate()
        val key = notificationKey()

        assertTrue(gate.shouldUpdate(key, positionMs = 10_000L, isPlaying = true, nowMs = 1_000L))
        assertTrue(gate.shouldUpdate(key, positionMs = 10_400L, isPlaying = false, nowMs = 1_100L))
        assertTrue(gate.shouldUpdate(key, positionMs = 20_000L, isPlaying = true, nowMs = 1_200L))
    }

    @Test
    fun explicitRefreshAlwaysPassesTheGate() {
        val gate = NotificationUpdateGate()
        val key = notificationKey()

        assertTrue(gate.shouldUpdate(key, positionMs = 0L, isPlaying = true, nowMs = 1_000L))
        assertTrue(gate.shouldUpdate(key, positionMs = 100L, isPlaying = true, nowMs = 1_050L, force = true))
    }

    private fun notificationKey(
        isPlaying: Boolean = true,
        playWhenReady: Boolean = isPlaying,
        title: String = "Track"
    ) = NotificationUpdateKey(
        mediaId = "track-1",
        title = title,
        artist = "Artist",
        album = "Album",
        artworkUri = null,
        durationMs = 180_000L,
        playbackState = 3,
        isPlaying = isPlaying,
        playWhenReady = playWhenReady,
        mediaItemIndex = 0,
        mediaItemCount = 1,
        repeatMode = 0,
        shuffleEnabled = false,
        playbackSpeed = 1f,
        queueIndex = 0,
        queueCount = 1,
        isFavorite = false
    )
}
