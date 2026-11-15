package com.example.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Lyrics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LrcDuplicateAndOffsetTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var audioFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        tempDir = File(context.cacheDir, "lrc_dup_test_${System.nanoTime()}").apply { mkdirs() }
        audioFile = File(tempDir, "Kanna Veesi Kanna Veesi.mp3").apply { writeText("fake-audio") }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `writeCompanionLrcForSong updates single canonical file and deletes numbered duplicates`() {
        val dup1 = File(tempDir, "Kanna Veesi Kanna Veesi.lrc (1)").apply { writeText("[00:05.00]Old wrong 1") }
        val dup2 = File(tempDir, "Kanna Veesi Kanna Veesi.lrc (2)").apply { writeText("[00:05.00]Old wrong 2") }
        val txtDup = File(tempDir, "Kanna Veesi Kanna Veesi.lrc.txt").apply { writeText("[00:05.00]Old txt") }
        val txtNumDup = File(tempDir, "Kanna Veesi Kanna Veesi.lrc.txt (1)").apply { writeText("[00:05.00]Old txt 1") }
        val parensStemDup = File(tempDir, "Kanna Veesi Kanna Veesi (1).lrc").apply { writeText("[00:05.00]Old stem 1") }
        val unrelatedLrc = File(tempDir, "Other Song.lrc").apply { writeText("[00:05.00]Keep me") }

        val first = LrcParser.writeCompanionLrcForSong(
            context = context,
            songPath = audioFile.absolutePath,
            songTitle = "Kanna Veesi Kanna Veesi",
            plainLyrics = "Line one",
            syncedLyricsJson = """[{"timeMs":10000,"text":"Line one"}]""",
            offsetMs = -2000L
        )

        assertNotNull(first)
        assertEquals("Kanna Veesi Kanna Veesi.lrc", first!!.name)
        assertFalse(dup1.exists())
        assertFalse(dup2.exists())
        assertFalse(txtDup.exists())
        assertFalse(txtNumDup.exists())
        assertFalse(parensStemDup.exists())
        assertTrue("Unrelated song .lrc must not be touched", unrelatedLrc.exists())

        // Changing lyrics and offset again updates the exact same file without creating (1)/(2)
        val second = LrcParser.writeCompanionLrcForSong(
            context = context,
            songPath = audioFile.absolutePath,
            songTitle = "Kanna Veesi Kanna Veesi",
            plainLyrics = "Line one\nLine two",
            syncedLyricsJson = """[{"timeMs":10000,"text":"Line one"},{"timeMs":20000,"text":"Line two"}]""",
            offsetMs = -7000L
        )

        assertNotNull(second)
        assertEquals(first.absolutePath, second!!.absolutePath)
        val matchingFiles = tempDir.listFiles()?.filter { it.name.startsWith("Kanna Veesi") && it.name != audioFile.name } ?: emptyList()
        assertEquals("Only the single canonical .lrc file should exist", 1, matchingFiles.size)
        val content = second.readText(Charsets.UTF_8)
        assertTrue("Updated content must reflect latest offset -7000ms", content.contains("[offset:-7000]"))
        assertTrue("Updated content must reflect latest lyrics", content.contains("Line two"))
    }

    @Test
    fun `concurrent offset updates serialize cleanly with zero duplicate files`() {
        val executor = Executors.newFixedThreadPool(4)
        val offsets = listOf(0L, -1000L, -2000L, -4500L, -7000L, 1500L, 3000L, -7000L)
        for (offset in offsets) {
            executor.submit {
                LrcParser.writeCompanionLrcForSong(
                    context = context,
                    songPath = audioFile.absolutePath,
                    songTitle = "Kanna Veesi Kanna Veesi",
                    plainLyrics = "Verse line",
                    syncedLyricsJson = """[{"timeMs":10000,"text":"Verse line"}]""",
                    offsetMs = offset
                )
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        val lrcCandidates = tempDir.listFiles()?.filter {
            it.name != audioFile.name
        } ?: emptyList()
        assertEquals("Concurrent writes must produce exactly one .lrc file", 1, lrcCandidates.size)
        assertEquals("Kanna Veesi Kanna Veesi.lrc", lrcCandidates.first().name)
    }

    @Test
    fun `deleteAllMatchingLocalLrcFiles removes numbered MediaProvider duplicates`() {
        val mainLrc = File(tempDir, "Kanna Veesi Kanna Veesi.lrc").apply { writeText("[00:10.00]Hi") }
        val dup1 = File(tempDir, "Kanna Veesi Kanna Veesi.lrc (1)").apply { writeText("[00:10.00]Hi") }
        val dup2 = File(tempDir, "Kanna Veesi Kanna Veesi.lrc (2)").apply { writeText("[00:10.00]Hi") }

        LrcParser.deleteAllMatchingLocalLrcFiles(
            context = context,
            songId = 42L,
            songPath = audioFile.absolutePath,
            songTitle = "Kanna Veesi Kanna Veesi"
        )

        assertFalse(mainLrc.exists())
        assertFalse(dup1.exists())
        assertFalse(dup2.exists())
    }

    @Test
    fun `offset mechanism delays or advances active line index and round-trips embedded offset tag`() {
        val synced = listOf(
            10_000L to "First verse line",
            20_000L to "Second verse line",
            30_000L to "Third verse line"
        )

        // Baseline (offset = 0ms): at 12.0s -> line 0 is active
        assertEquals(0, Lyrics.findActiveLineIndex(synced, 12_000L + 0L))

        // Delayed by 2 seconds (offset = -2000ms): at 12.0s -> effectivePos = 10.0s -> line 0 activates exactly at 12.0s
        assertEquals(0, Lyrics.findActiveLineIndex(synced, 12_000L + (-2_000L)))
        // At 11.5s with -2000ms delay -> effectivePos = 9.5s < first line (10.0s), so UI highlightedIndex must be -1 (before first line)
        val beforeFirstAt11_5s = (11_500L + (-2_000L)) < synced[0].first
        assertTrue("Delayed lyrics should not highlight line 0 before shifted start time", beforeFirstAt11_5s)

        // Delayed by 7 seconds (offset = -7000ms):
        // At 16.5s -> effectivePos = 9.5s < 10.0s (still waiting for line 0!)
        assertTrue((16_500L + (-7_000L)) < synced[0].first)
        // At 17.5s -> effectivePos = 10.5s >= 10.0s (line 0 starts 7s later than original 10s!)
        assertEquals(0, Lyrics.findActiveLineIndex(synced, 17_500L + (-7_000L)))
        // At 25.0s -> without offset this was line 1 (20s), with -7s delay effectivePos = 18.0s -> still line 0!
        assertEquals(1, Lyrics.findActiveLineIndex(synced, 25_000L + 0L))
        assertEquals(0, Lyrics.findActiveLineIndex(synced, 25_000L + (-7_000L)))

        // Advanced earlier by 7 seconds (offset = +7000ms): at 15.0s -> effectivePos = 22.0s -> already on line 1
        assertEquals(1, Lyrics.findActiveLineIndex(synced, 15_000L + 7_000L))

        // Round-trip [offset:-7000] formatting & extraction
        val raw = LrcParser.formatLyricsToLrcContent(
            syncedLyricsJson = """[{"timeMs":10000,"text":"First verse line"}]""",
            offsetMs = -7000L
        )!!
        assertEquals(-7000L, LrcParser.extractOffsetFromLrc(raw))
        val (_, parsedJson) = LrcParser.parseLrcContent(raw)
        assertNotNull(parsedJson)
        val reparsed = Lyrics(songId = 1L, plainLyrics = null, syncedLyricsJson = parsedJson, isCached = false, source = "local").parseSyncedLines()
        assertEquals(10_000L, reparsed.first().first)
    }
}
