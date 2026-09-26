package com.example

import com.example.data.model.Song
import com.example.util.TagCleaner
import org.junit.Assert.*
import org.junit.Test

class TagCleanerAndIndexingUnitTest {

    @Test
    fun testTagCleanerSongProperties() {
        val rawSong = Song(
            id = 1L,
            title = "01. Bohemian Rhapsody (Official Video) [Remastered 2011]",
            artist = "Queen - Topic / Vevo",
            album = "A Night at the Opera (Deluxe Edition 2011)",
            durationMs = 354000,
            path = "/storage/emulated/0/Music/Queen/01 Bohemian Rhapsody.mp3",
            contentUriString = "content://media/external/audio/media/1",
            year = 1975,
            genre = "Rock"
        )

        assertEquals("Bohemian Rhapsody", rawSong.displayTitle)
        assertEquals("Queen", TagCleaner.cleanArtist(rawSong.artist))
        assertEquals("A Night at the Opera", TagCleaner.cleanAlbum(rawSong.album))
    }

    @Test
    fun testTagCleanerEdgeCases() {
        val unknownSong = Song(
            id = 2L,
            title = "",
            artist = "<unknown>",
            album = "unknown",
            durationMs = 120000,
            path = "/storage/emulated/0/Music/Sample Track.flac",
            contentUriString = "content://media/external/audio/media/2"
        )

        assertEquals("Sample Track", unknownSong.displayTitle)
        assertEquals("Unknown Artist", unknownSong.displayArtist)
        assertEquals("Unknown Album", unknownSong.displayAlbum)
    }

    @Test
    fun testTagCleanerFormatting() {
        assertEquals("Track One", TagCleaner.cleanTitle("01 - Track One (Official Audio)", "01 - Track One.mp3"))
        assertEquals("Artist Name", TagCleaner.cleanArtist("Artist Name - Topic"))
        assertEquals("Album Name", TagCleaner.cleanAlbum("Album Name [Deluxe Edition]"))
    }
}
