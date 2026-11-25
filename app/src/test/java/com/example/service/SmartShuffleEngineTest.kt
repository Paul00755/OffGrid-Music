package com.example.service

import com.example.data.local.PlaybackHistoryEntity
import com.example.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartShuffleEngineTest {

    private fun song(
        id: Long,
        artist: String = "Artist $id",
        album: String = "Album $id",
        genre: String = "Pop",
        playCount: Int = 0,
        lastPlayed: Long = 0L,
        isFavorite: Boolean = false
    ) = Song(
        id = id,
        title = "Track $id",
        artist = artist,
        album = album,
        albumId = id,
        durationMs = 200_000L,
        path = "/music/track_$id.mp3",
        contentUriString = "content://media/external/audio/media/$id",
        genre = genre,
        playCount = playCount,
        lastPlayed = lastPlayed,
        isFavorite = isFavorite
    )

    @Test
    fun reorderQueue_preservesAllSongsAndPinsAnchorAtIndexZero() {
        val now = 1_700_000_000_000L
        val songs = (1L..15L).map { id ->
            song(
                id = id,
                artist = "Artist ${(id % 5) + 1}",
                genre = if (id % 2L == 0L) "Indie" else "Electronic",
                playCount = (id % 6).toInt()
            )
        }
        val anchor = songs[7]
        val profile = SmartShuffleEngine.buildListeningProfile(songs, emptyList(), now)

        val reordered = SmartShuffleEngine.reorderQueue(
            songs = songs,
            anchorSong = anchor,
            profile = profile,
            nowMs = now,
            random = Random(42)
        )

        assertEquals(songs.size, reordered.size)
        assertEquals(anchor.id, reordered.first().id)
        assertEquals(songs.map { it.id }.toSet(), reordered.map { it.id }.toSet())
    }

    @Test
    fun reorderQueue_prioritizesPreferredGenreAndAvoidsSkippedOrJustPlayedTracks() {
        val now = 1_700_000_000_000L
        val twoDaysAgo = now - 2L * 24L * 60L * 60L * 1000L
        val fiveMinsAgo = now - 5L * 60L * 1000L

        val preferredSongs = (1L..6L).map { id ->
            song(
                id = id,
                artist = "SynthArtist $id",
                genre = "Synthwave",
                playCount = 12,
                lastPlayed = twoDaysAgo,
                isFavorite = true
            )
        }
        val skippedSongs = (7L..12L).map { id ->
            song(
                id = id,
                artist = "MetalArtist $id",
                genre = "Heavy Metal",
                playCount = 0,
                lastPlayed = fiveMinsAgo,
                isFavorite = false
            )
        }
        val allSongs = skippedSongs + preferredSongs

        val history = buildList {
            preferredSongs.forEach { s ->
                repeat(4) { idx ->
                    add(
                        PlaybackHistoryEntity(
                            trackId = s.id,
                            startedAt = twoDaysAgo - idx * 60_000L,
                            endedAt = twoDaysAgo - idx * 60_000L + 195_000L,
                            listenedDurationMs = 195_000L,
                            completionPercentage = 0.96f,
                            isQualified = true
                        )
                    )
                }
            }
            skippedSongs.forEach { s ->
                repeat(3) { idx ->
                    add(
                        PlaybackHistoryEntity(
                            trackId = s.id,
                            startedAt = fiveMinsAgo - idx * 20_000L,
                            endedAt = fiveMinsAgo - idx * 20_000L + 6_000L,
                            listenedDurationMs = 6_000L,
                            completionPercentage = 0.05f,
                            isQualified = false
                        )
                    )
                }
            }
        }

        val profile = SmartShuffleEngine.buildListeningProfile(allSongs, history, now)
        assertTrue(
            "Expected synthwave genre weight > heavy metal",
            (profile.genreWeights["synthwave"] ?: 0f) > (profile.genreWeights["heavy metal"] ?: 0f)
        )

        val reordered = SmartShuffleEngine.reorderQueue(
            songs = allSongs,
            anchorSong = null,
            profile = profile,
            nowMs = now,
            random = Random(7)
        )

        val topHalfPreferredCount = reordered.take(6).count { it.genre == "Synthwave" }
        assertTrue(
            "Expected at least 5 of top 6 tracks to be from preferred genre, got $topHalfPreferredCount",
            topHalfPreferredCount >= 5
        )
    }

    @Test
    fun reorderQueue_preventsConsecutiveSameArtistClumping() {
        val now = 1_700_000_000_000L
        val songs = listOf(
            song(1, artist = "Artist A", album = "A1", genre = "Rock", playCount = 5),
            song(2, artist = "Artist A", album = "A1", genre = "Rock", playCount = 5),
            song(3, artist = "Artist A", album = "A2", genre = "Rock", playCount = 5),
            song(4, artist = "Artist B", album = "B1", genre = "Rock", playCount = 5),
            song(5, artist = "Artist B", album = "B2", genre = "Rock", playCount = 5),
            song(6, artist = "Artist C", album = "C1", genre = "Rock", playCount = 5),
            song(7, artist = "Artist C", album = "C2", genre = "Rock", playCount = 5),
            song(8, artist = "Artist D", album = "D1", genre = "Rock", playCount = 5)
        )

        val profile = SmartShuffleEngine.buildListeningProfile(songs, emptyList(), now)
        val reordered = SmartShuffleEngine.reorderQueue(
            songs = songs,
            anchorSong = songs.first(),
            profile = profile,
            nowMs = now,
            random = Random(99)
        )

        val backToBackSameArtist = reordered.zipWithNext().count { (a, b) -> a.artist == b.artist }
        assertEquals(
            "Expected zero back-to-back tracks from the same artist when plenty of artists exist",
            0,
            backToBackSameArtist
        )
    }
}
