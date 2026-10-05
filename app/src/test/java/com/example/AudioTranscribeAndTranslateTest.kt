package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.Lyrics
import com.example.service.transcription.AudioTranscribeMode
import com.example.service.transcription.LyricsTranslationCache
import com.example.service.transcription.TranscribeLanguage
import com.example.util.LrcParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioTranscribeAndTranslateTest {

    private lateinit var context: Context
    private lateinit var preferences: UserPreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = UserPreferencesRepository(context)
    }

    @Test
    fun testSettingsPersistenceAndFallbacks() = runBlocking {
        // Default values
        val defaultMode = preferences.audioTranscribeMode.first()
        val defaultLang = preferences.audioTranscribeTargetLang.first()

        assertEquals("OFF", defaultMode)
        assertEquals("ta", defaultLang)

        // Set Transcribe Audio mode and Tamil
        preferences.setAudioTranscribeMode("TRANSCRIBE")
        preferences.setAudioTranscribeTargetLang("ta")
        assertEquals("TRANSCRIBE", preferences.audioTranscribeMode.first())
        assertEquals("ta", preferences.audioTranscribeTargetLang.first())

        // Set Translate Lyrics mode and Hindi
        preferences.setAudioTranscribeMode("TRANSLATE")
        preferences.setAudioTranscribeTargetLang("hi")
        assertEquals("TRANSLATE", preferences.audioTranscribeMode.first())
        assertEquals("hi", preferences.audioTranscribeTargetLang.first())

        // Set Malayalam and English
        preferences.setAudioTranscribeTargetLang("ml")
        assertEquals("ml", preferences.audioTranscribeTargetLang.first())
        preferences.setAudioTranscribeTargetLang("en")
        assertEquals("en", preferences.audioTranscribeTargetLang.first())

        // Verify Enum parsing helpers
        assertEquals(AudioTranscribeMode.TRANSCRIBE, AudioTranscribeMode.fromId("TRANSCRIBE"))
        assertEquals(AudioTranscribeMode.TRANSLATE, AudioTranscribeMode.fromId("TRANSLATE"))
        assertEquals(AudioTranscribeMode.OFF, AudioTranscribeMode.fromId("OFF"))
        assertEquals(AudioTranscribeMode.OFF, AudioTranscribeMode.fromId("UNKNOWN"))

        assertEquals(TranscribeLanguage.TAMIL, TranscribeLanguage.fromCode("ta"))
        assertEquals(TranscribeLanguage.HINDI, TranscribeLanguage.fromCode("hi"))
        assertEquals(TranscribeLanguage.MALAYALAM, TranscribeLanguage.fromCode("ml"))
        assertEquals(TranscribeLanguage.ENGLISH, TranscribeLanguage.fromCode("en"))
        assertEquals(TranscribeLanguage.TAMIL, TranscribeLanguage.fromCode(null))
    }

    @Test
    fun testLyricsTranslationCacheKeyingAndInvalidation() {
        val songId = 9991L
        val originalPlain = "Uyirin uyire\nUnathu vizhiyil"
        val originalSynced = """[{"timeMs":13200,"text":"Uyirin uyire"},{"timeMs":18400,"text":"Unathu vizhiyil"}]"""

        val sourceHash = LyricsTranslationCache.computeSourceLyricsHash(originalPlain, originalSynced)
        assertNotNull(sourceHash)
        assertTrue(sourceHash.isNotEmpty())

        val translatedPlain = "உயிரின் உயிரே\nஉனது விழியில்"
        val translatedSynced = """[{"timeMs":13200,"text":"உயிரின் உயிரே"},{"timeMs":18400,"text":"உனது விழியில்"}]"""

        // Put into cache
        LyricsTranslationCache.put(
            context = context,
            songId = songId,
            mode = AudioTranscribeMode.TRANSLATE,
            targetLang = TranscribeLanguage.TAMIL,
            sourceLyricsHash = sourceHash,
            plainLyrics = translatedPlain,
            syncedLyricsJson = translatedSynced
        )

        // Retrieve with same hash
        val cached = LyricsTranslationCache.get(
            context = context,
            songId = songId,
            mode = AudioTranscribeMode.TRANSLATE,
            targetLang = TranscribeLanguage.TAMIL,
            sourceLyricsHash = sourceHash
        )

        assertNotNull(cached)
        assertEquals(translatedPlain, cached?.plainLyrics)
        assertEquals(translatedSynced, cached?.syncedLyricsJson)
        assertEquals(TranscribeLanguage.TAMIL, cached?.language)
        assertEquals(AudioTranscribeMode.TRANSLATE, cached?.mode)

        // If source lyrics change, hash changes and cached entry is NOT returned
        val modifiedPlain = "Uyirin uyire modified\nUnathu vizhiyil"
        val newSourceHash = LyricsTranslationCache.computeSourceLyricsHash(modifiedPlain, originalSynced)
        assertNotEquals(sourceHash, newSourceHash)

        val cachedAfterSourceChange = LyricsTranslationCache.get(
            context = context,
            songId = songId,
            mode = AudioTranscribeMode.TRANSLATE,
            targetLang = TranscribeLanguage.TAMIL,
            sourceLyricsHash = newSourceHash
        )
        assertNull(cachedAfterSourceChange)
    }

    @Test
    fun testTimestampPreservationAndLrcParsing() {
        val sampleLrc = """
            [00:13.20] Uyirin uyire
            [00:18.40] Unathu vizhiyil
            [00:24.00] Ennai maraithen
        """.trimIndent()

        val (plain, syncedJson) = LrcParser.parseLrcContent(sampleLrc)
        assertNotNull(syncedJson)

        val dummyLyrics = Lyrics(
            songId = 1L,
            plainLyrics = plain,
            syncedLyricsJson = syncedJson
        )
        val parsedLines = dummyLyrics.parseSyncedLines()
        assertEquals(3, parsedLines.size)
        assertEquals(13200L, parsedLines[0].first)
        assertEquals("Uyirin uyire", parsedLines[0].second)
        assertEquals(18400L, parsedLines[1].first)
        assertEquals("Unathu vizhiyil", parsedLines[1].second)
        assertEquals(24000L, parsedLines[2].first)
        assertEquals("Ennai maraithen", parsedLines[2].second)

        // Format back to LRC text and ensure timestamps are strictly preserved
        val reformattedLrc = LrcParser.formatSyncedJsonToLrc(syncedJson)
        assertNotNull(reformattedLrc)
        assertTrue(reformattedLrc!!.contains("[00:13.20]"))
        assertTrue(reformattedLrc.contains("[00:18.40]"))
        assertTrue(reformattedLrc.contains("[00:24.00]"))

        // Simulate translation preserving timestamps exactly
        val translatedLrc = """
            [00:13.20] உயிரின் உயிரே
            [00:18.40] உனது விழியில்
            [00:24.00] என்னை மறைத்தேன்
        """.trimIndent()

        val (translatedPlain, translatedSyncedJson) = LrcParser.parseLrcContent(translatedLrc)
        val translatedDummy = Lyrics(
            songId = 1L,
            plainLyrics = translatedPlain,
            syncedLyricsJson = translatedSyncedJson
        )
        val translatedLines = translatedDummy.parseSyncedLines()
        assertEquals(3, translatedLines.size)
        assertEquals(13200L, translatedLines[0].first)
        assertEquals("உயிரின் உயிரே", translatedLines[0].second)
        assertEquals(18400L, translatedLines[1].first)
        assertEquals("உனது விழியில்", translatedLines[1].second)
        assertEquals(24000L, translatedLines[2].first)
        assertEquals("என்னை மறைத்தேன்", translatedLines[2].second)
    }

    @Test
    fun testAuthoritativeLyricsSyncOffsetCalculation() {
        val lyrics = Lyrics(
            songId = 100L,
            plainLyrics = "Line 1\nLine 2\nLine 3",
            syncedLyricsJson = """[{"timeMs":10000,"text":"Line 1"},{"timeMs":20000,"text":"Line 2"},{"timeMs":30000,"text":"Line 3"}]"""
        )
        val syncedLines = lyrics.parseSyncedLines()
        assertEquals(3, syncedLines.size)

        // Case 1: Zero offset, position at 12s -> active line is Line 1 (10s)
        var positionMs = 12000L
        var syncOffsetMs = 0L
        var effectivePositionMs = (positionMs + syncOffsetMs).coerceAtLeast(0L)
        var activeIndex = syncedLines.indexOfLast { effectivePositionMs >= it.first }.coerceAtLeast(0)
        assertEquals(0, activeIndex)

        // Case 2: Positive offset +1.2s (offsetMs = 1200L) -> effectivePosition = 13200ms -> still Line 1
        syncOffsetMs = 1200L
        effectivePositionMs = (positionMs + syncOffsetMs).coerceAtLeast(0L)
        assertEquals(13200L, effectivePositionMs)

        // Case 3: Position at 19s, negative offset -0.5s -> effectivePosition = 18500ms -> activeIndex is Line 1 (10s)
        positionMs = 19000L
        syncOffsetMs = -500L
        effectivePositionMs = (positionMs + syncOffsetMs).coerceAtLeast(0L)
        assertEquals(18500L, effectivePositionMs)
        activeIndex = syncedLines.indexOfLast { effectivePositionMs >= it.first }.coerceAtLeast(0)
        assertEquals(0, activeIndex)

        // Case 4: Position at 19.5s, positive offset +1.0s -> effectivePosition = 20500ms -> activeIndex is Line 2 (20s)
        positionMs = 19500L
        syncOffsetMs = 1000L
        effectivePositionMs = (positionMs + syncOffsetMs).coerceAtLeast(0L)
        assertEquals(20500L, effectivePositionMs)
        activeIndex = syncedLines.indexOfLast { effectivePositionMs >= it.first }.coerceAtLeast(0)
        assertEquals(1, activeIndex)
    }
}
