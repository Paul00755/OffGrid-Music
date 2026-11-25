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

    @Test
    fun testMultiGenreArtistFavoritesUnplayedAndDurationFilters() {
        val nowMs = System.currentTimeMillis()
        val library = listOf(
            Song(
                id = 10L,
                title = "Short Fav Rock",
                artist = "Band X",
                album = "A1",
                genre = "Alt / Rock",
                durationMs = 150_000L, // < 3m (SHORT)
                path = "/m/10.mp3",
                contentUriString = "content://media/10",
                playCount = 0,
                isFavorite = true,
                dateAdded = (nowMs / 1000L) - 86400L // MediaStore seconds (1 day ago)
            ),
            Song(
                id = 11L,
                title = "Long Pop Track",
                artist = "Singer Y",
                album = "A2",
                genre = "Pop",
                durationMs = 360_000L, // > 5m (LONG)
                path = "/m/11.mp3",
                contentUriString = "content://media/11",
                playCount = 4,
                isFavorite = false,
                dateAdded = nowMs - 1000L * 60 * 60 * 24 * 20
            ),
            Song(
                id = 12L,
                title = "Medium Jazz",
                artist = "Band X",
                album = "A3",
                genre = "Jazz",
                durationMs = 240_000L, // 3-5m (MEDIUM)
                path = "/m/12.mp3",
                contentUriString = "content://media/12",
                playCount = 0,
                isFavorite = true,
                dateAdded = (nowMs / 1000L) - (86400L * 60L) // 60 days ago in seconds
            )
        )

        val multiGenre = SmartPlaylist(name = "Rock & Pop", genreFilter = "Rock, Pop")
        assertEquals(listOf(10L, 11L), multiGenre.filterSongs(library).map { it.id })

        val favUnplayedShort = SmartPlaylist(
            name = "Quick Unplayed Favs",
            artistFilter = "Band X",
            favoritesOnly = true,
            unplayedOnly = true,
            durationFilter = "SHORT",
            timeframeDays = 7
        )
        val filtered = favUnplayedShort.filterSongs(library)
        assertEquals(1, filtered.size)
        assertEquals(10L, filtered[0].id)

        val longOnly = SmartPlaylist(name = "Long", durationFilter = "LONG")
        assertEquals(listOf(11L), longOnly.filterSongs(library).map { it.id })
    }

    @Test
    fun testCloudUserSettingsNowPlayingSeekButtonDefaultsAndRoundTrip() {
        val defaults = com.example.data.sync.CloudUserSettings()
        assertFalse(defaults.showNowPlayingSeekButtons)
        assertEquals(10, defaults.nowPlayingBackwardSeconds)
        assertEquals(10, defaults.nowPlayingForwardSeconds)

        val custom = defaults.copy(
            showNowPlayingSeekButtons = true,
            nowPlayingBackwardSeconds = 5,
            nowPlayingForwardSeconds = 30
        )
        val roundTripped = com.example.data.sync.CloudUserSettings.fromMap(custom.toMap())
        assertTrue(roundTripped.showNowPlayingSeekButtons)
        assertEquals(5, roundTripped.nowPlayingBackwardSeconds)
        assertEquals(30, roundTripped.nowPlayingForwardSeconds)
    }
}

