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

    @Test
    fun testUnwantedSourceAndReleaseSuffixRemoval() {
        // Requirement 3 & 4 examples
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name - Tamil - Masstamilan", "Song Name - Tamil - Masstamilan.mp3"))
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name - pagalworld - video song", "Song Name - pagalworld - video song.mp3"))
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name - pagal world - video song", "Song Name - pagal world - video song.mp3"))
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name - movie name", "Song Name - movie name.mp3"))
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name - Malayalam", "Song Name - Malayalam.mp3"))
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name [Tamil] (Masstamilan)", "Song Name [Tamil] (Masstamilan).mp3"))
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name-masstamilan", "Song Name-masstamilan.mp3"))
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name_masstamilan", "Song Name_masstamilan.mp3"))
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name - Masstamilan.dev", "Song Name - Masstamilan.dev.mp3"))
        assertEquals("Song Name", TagCleaner.cleanTitle("Song Name - 320kbps", "Song Name - 320kbps.mp3"))
    }

    @Test
    fun testGenuineSongNamesPreservedWithoutBlindRemoval() {
        // Crucial requirement: Do NOT blindly remove words when they are part of the title
        assertEquals("Video Killed the Radio Star", TagCleaner.cleanTitle("Video Killed the Radio Star", "Video Killed the Radio Star.mp3"))
        assertEquals("The River Song", TagCleaner.cleanTitle("The River Song", "The River Song.mp3"))
        assertEquals("Love Song", TagCleaner.cleanTitle("Love Song", "Love Song.mp3"))
        assertEquals("Tamil Anthem", TagCleaner.cleanTitle("Tamil Anthem", "Tamil Anthem.mp3"))
        assertEquals("Video Song", TagCleaner.cleanTitle("Video Song", "Video Song.mp3"))
    }

    @Test
    fun testManualUserEditedMetadataPreservation() {
        // When user manually edited a title, displayTitle must return the manual title without modification
        val editedSong = Song(
            id = 10L,
            title = "Ennodu Nee Irundhaal",
            artist = "A.R. Rahman",
            album = "I",
            durationMs = 340000,
            path = "/storage/emulated/0/Music/Ennodu Nee Irundhaal - Tamil - Masstamilan.mp3",
            contentUriString = "content://media/external/audio/media/10",
            isManuallyEdited = true
        )

        assertEquals("Ennodu Nee Irundhaal", editedSong.displayTitle)
        assertEquals("A.R. Rahman", editedSong.displayArtist)
        assertEquals("I", editedSong.displayAlbum)
    }
}
