package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.ui.components.ArtworkResolutionCache
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SonicStudioAndPerformanceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SonicStudioEngine.clearAbLoop()
        ArtworkColorExtractor.clearCache()
    }

    @Test
    fun `Lyrics parseSyncedLines is memoized and findActiveLineIndex matches linear scan`() {
        val lrcContent = """
            [00:05.00] First line
            [00:12.50] Second line
            [00:20.00] Third line
            [00:35.20] Fourth line
        """.trimIndent()
        val lyrics = Lyrics(songId = 42L, plainLyrics = lrcContent)

        val firstParse = lyrics.parseSyncedLines()
        val secondParse = lyrics.parseSyncedLines()

        assertSame("parseSyncedLines should return the memoized list instance", firstParse, secondParse)
        assertEquals(4, firstParse.size)

        val testPositions = listOf(0L, 4999L, 5000L, 8000L, 12500L, 19999L, 20000L, 35200L, 60000L)
        for (pos in testPositions) {
            val expected = firstParse.indexOfLast { pos >= it.first }.coerceAtLeast(0)
            val actual = Lyrics.findActiveLineIndex(firstParse, pos)
            assertEquals("Mismatch at position $pos ms", expected, actual)
        }
    }

    @Test
    fun `ArtworkResolutionCache trackIdentity matches standard SHA-256 hex format`() {
        val songId = 999L
        val path = "/storage/emulated/0/Music/OffGrid/Track01.mp3"
        val identity = ArtworkResolutionCache.trackIdentity(songId, path)

        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals("id:$songId:$expectedDigest", identity)
    }

    @Test
    fun `ArtworkColorExtractor findProminentColor extracts vibrant color from large bitmap`() {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(AndroidColor.rgb(220, 40, 90))

        val extracted = ArtworkColorExtractor.findProminentColor(bmp)
        bmp.recycle()

        assertNotNull("Expected non-null prominent color", extracted)
    }

    @Test
    fun `SonicStudioEngine A-B Looper enforces valid bounds and triggers loop-back`() {
        val songId = 101L
        SonicStudioEngine.setLoopPointA(songId, 10_000L, 180_000L)
        assertFalse(SonicStudioEngine.abLoopState.value.isActive)

        // Setting Point B before Point A should fail
        val invalidB = SonicStudioEngine.setLoopPointB(songId, 8_000L, 180_000L)
        assertFalse(invalidB)

        // Setting valid Point B activates loop
        val validB = SonicStudioEngine.setLoopPointB(songId, 25_000L, 180_000L)
        assertTrue(validB)

        val state = SonicStudioEngine.abLoopState.value
        assertTrue(state.isActive)
        assertFalse(state.shouldLoopBack(songId, 24_900L))
        assertTrue(state.shouldLoopBack(songId, 25_000L))
        assertFalse(state.shouldLoopBack(999L, 26_000L))
    }

    @Test
    fun `SonicStudioEngine bookmarks persist and sort chronologically`() {
        val songId = 505L
        SonicStudioEngine.addBookmark(context, songId, 45_000L, "Guitar Solo")
        SonicStudioEngine.addBookmark(context, songId, 12_000L, "Intro Drop")

        val bookmarks = SonicStudioEngine.getBookmarks(context, songId)
        assertEquals(2, bookmarks.size)
        assertEquals("Intro Drop", bookmarks[0].label)
        assertEquals("0:12", bookmarks[0].formattedTime)
        assertEquals("Guitar Solo", bookmarks[1].label)
        assertEquals("0:45", bookmarks[1].formattedTime)

        SonicStudioEngine.removeBookmark(context, songId, bookmarks[0].id)
        val remaining = SonicStudioEngine.getBookmarks(context, songId)
        assertEquals(1, remaining.size)
        assertEquals("Guitar Solo", remaining[0].label)
    }

    @Test
    fun `SonicStudioEngine buildSonicDnaRadioQueue ranks matching artist and genre highest`() {
        val seed = Song(
            id = 1L,
            title = "Midnight Synth",
            artist = "Neon Pulse",
            album = "Cyber Horizons",
            durationMs = 210_000L,
            contentUriString = "content://media/external/audio/media/1",
            path = "/music/synth/Midnight_Synth.mp3",
            genre = "Synthwave Electronic",
            year = 2024
        )
        val highMatch = Song(
            id = 2L,
            title = "Starlight Drive",
            artist = "Neon Pulse",
            album = "Cyber Horizons",
            durationMs = 205_000L,
            contentUriString = "content://media/external/audio/media/2",
            path = "/music/synth/Starlight_Drive.mp3",
            genre = "Synthwave Electronic",
            year = 2024
        )
        val lowMatch = Song(
            id = 3L,
            title = "Acoustic Morning",
            artist = "Folk Ensemble",
            album = "Woodland",
            durationMs = 420_000L,
            contentUriString = "content://media/external/audio/media/3",
            path = "/music/folk/Acoustic_Morning.mp3",
            genre = "Country Folk",
            year = 1978
        )

        val radio = SonicStudioEngine.buildSonicDnaRadioQueue(
            seedSong = seed,
            candidateSongs = listOf(lowMatch, highMatch, seed),
            maxTracks = 10
        )

        assertEquals(3, radio.size)
        assertEquals(seed.id, radio[0].song.id)
        assertEquals(highMatch.id, radio[1].song.id)
        assertEquals(lowMatch.id, radio[2].song.id)
        assertTrue(radio[1].score > radio[2].score)
    }
}
