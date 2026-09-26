package com.example

import com.example.data.model.Song
import com.example.data.model.SmartPlaylist
import org.junit.Assert.*
import org.junit.Test

class SmartPlaylistUnitTest {

    private val sampleSongs = listOf(
        Song(
            id = 1L,
            title = "Rock Anthem",
            artist = "Band A",
            album = "Album 1",
            genre = "Rock",
            durationMs = 200000,
            path = "/storage/emulated/0/Music/1.mp3",
            contentUriString = "content://media/external/audio/media/1",
            playCount = 10,
            dateAdded = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 2 // 2 days ago
        ),
        Song(
            id = 2L,
            title = "Pop Hit",
            artist = "Singer B",
            album = "Album 2",
            genre = "Pop",
            durationMs = 180000,
            path = "/storage/emulated/0/Music/2.mp3",
            contentUriString = "content://media/external/audio/media/2",
            playCount = 25,
            dateAdded = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 10 // 10 days ago
        ),
        Song(
            id = 3L,
            title = "Jazz Solo",
            artist = "Artist C",
            album = "Album 3",
            genre = "Jazz",
            durationMs = 300000,
            path = "/storage/emulated/0/Music/3.mp3",
            contentUriString = "content://media/external/audio/media/3",
            playCount = 2,
            dateAdded = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 40 // 40 days ago
        ),
        Song(
            id = 4L,
            title = "Heavy Rock",
            artist = "Band A",
            album = "Album 4",
            genre = "Rock",
            durationMs = 240000,
            path = "/storage/emulated/0/Music/4.mp3",
            contentUriString = "content://media/external/audio/media/4",
            playCount = 15,
            dateAdded = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 5 // 5 days ago
        )
    )

    @Test
    fun testGenreFilter() {
        val rockSmart = SmartPlaylist(
            name = "Rock Only",
            genreFilter = "Rock"
        )
        val filtered = rockSmart.filterSongs(sampleSongs)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.genre.equals("Rock", ignoreCase = true) })
    }

    @Test
    fun testMinPlayCountFilter() {
        val highPlays = SmartPlaylist(
            name = "Heavy Rotation",
            minPlayCount = 10
        )
        val filtered = highPlays.filterSongs(sampleSongs)
        assertEquals(3, filtered.size) // 10, 25, 15
        assertTrue(filtered.all { it.playCount >= 10 })
    }

    @Test
    fun testTimeframeFilter() {
        val last7Days = SmartPlaylist(
            name = "Recent Week",
            timeframeDays = 7
        )
        val filtered = last7Days.filterSongs(sampleSongs)
        assertEquals(2, filtered.size) // 2 days ago and 5 days ago
    }

    @Test
    fun testMaxLimitAndSort() {
        val topRock = SmartPlaylist(
            name = "Top 1 Rock",
            genreFilter = "Rock",
            maxTrackLimit = 1,
            sortCriteria = "MOST_PLAYED"
        )
        val filtered = topRock.filterSongs(sampleSongs)
        assertEquals(1, filtered.size)
        assertEquals(4L, filtered[0].id) // Heavy Rock has playCount 15 vs 10
    }

    @Test
    fun testBadgesGeneration() {
        val smart = SmartPlaylist(
            name = "Test",
            genreFilter = "Rock",
            minPlayCount = 5,
            timeframeDays = 30,
            maxTrackLimit = 50,
            sortCriteria = "RECENTLY_ADDED"
        )
        val badges = smart.getCriteriaBadges()
        assertTrue(badges.contains("Genre: Rock"))
        assertTrue(badges.contains("Plays ≥ 5"))
        assertTrue(badges.contains("Last 30 Days"))
        assertTrue(badges.contains("Limit: 50"))
        assertTrue(badges.contains("Sort: Recently Added"))
    }
}
