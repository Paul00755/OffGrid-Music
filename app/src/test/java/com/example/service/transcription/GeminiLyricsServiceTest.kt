package com.example.service.transcription

import com.example.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class GeminiLyricsServiceTest {

    @Before
    fun setUp() {
        GeminiLyricsService.clearModelCacheForTesting()
    }

    @Test
    fun testAudioCapableFilterRejectsNonAudioModels() {
        val genContent = setOf("generateContent")
        assertTrue(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-4-neon", genContent))
        assertTrue(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-4-hydrogen", genContent))
        assertTrue(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-4-argon", genContent))
        assertTrue(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-3.8-flash", genContent))
        assertTrue(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-2.5-pro", genContent))
        assertTrue(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-2.5-flash", genContent))

        // Must reject non-audio / specialized / legacy models
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/text-embedding-004", genContent))
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-embedding-001", genContent))
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/imagen-3.0-generate-002", genContent))
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/veo-2.0-generate-001", genContent))
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-2.5-flash-preview-tts", genContent))
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-2.5-flash-image-preview", genContent))
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-2.5-flash-native-audio-preview-12-2025", genContent))
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-1.0-pro", genContent))
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemma-3-27b-it", genContent))
        assertFalse(GeminiLyricsService.isAudioTranscriptionCapableModel("models/gemini-2.5-flash", setOf("bidiGenerateContent")))
    }

    @Test
    fun testModelRankingFollowsHighestToNextBestTierOrder() {
        val genContent = setOf("generateContent")
        val shuffled = listOf(
            "models/gemini-2.0-flash" to genContent,
            "models/gemini-2.5-flash" to genContent,
            "models/gemini-3.8-flash" to genContent,
            "models/gemini-4-argon" to genContent,
            "models/gemini-4-neon" to genContent,
            "models/gemini-2.5-pro" to genContent,
            "models/gemini-4-hydrogen" to genContent,
            "models/gemini-3.5-pro" to genContent,
            "models/gemini-2.5-flash-preview-tts" to genContent,
            "models/gemini-embedding-001" to genContent
        )

        val ranked = GeminiLyricsService.rankAndSelectModels(shuffled)
        assertEquals(
            listOf(
                "gemini-4-neon",
                "gemini-4-hydrogen",
                "gemini-4-argon",
                "gemini-3.8-flash",
                "gemini-3.5-pro",
                "gemini-2.5-pro",
                "gemini-2.5-flash",
                "gemini-2.0-flash"
            ),
            ranked
        )
    }

    @Test
    fun testTimeoutAndQuotaFailoverAcrossDiscoveredModels() = runBlocking {
        val calledModels = mutableListOf<String>()
        val statuses = mutableListOf<String>()

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()

            if (req.method == "GET" && url.contains("/v1beta/models")) {
                val modelsJson = """
                    {
                      "models": [
                        {"name": "models/gemini-2.5-flash", "supportedGenerationMethods": ["generateContent"]},
                        {"name": "models/gemini-4-argon", "supportedGenerationMethods": ["generateContent"]},
                        {"name": "models/gemini-4-hydrogen", "supportedGenerationMethods": ["generateContent"]},
                        {"name": "models/gemini-4-neon", "supportedGenerationMethods": ["generateContent"]},
                        {"name": "models/gemini-embedding-001", "supportedGenerationMethods": ["embedContent"]}
                      ]
                    }
                """.trimIndent()
                return@Interceptor Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(modelsJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }

            val modelId = url.substringAfter("/models/").substringBefore(":")
            calledModels.add(modelId)

            when (modelId) {
                "gemini-4-neon" -> {
                    // Simulate exceeding the 15s response timeout
                    throw SocketTimeoutException("timeout waiting for gemini-4-neon")
                }
                "gemini-4-hydrogen" -> {
                    // Simulate rate limit / busy tier
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("""{"error":{"message":"Quota exceeded for model"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                "gemini-4-argon" -> {
                    val sseBody = """
                        data: {"candidates":[{"content":{"parts":[{"text":"[00:00.00] ♪\n[00:12.40] எட்டுத் திசையும் இசை கேட்குதே\n"}]}}]}

                        data: {"candidates":[{"content":{"parts":[{"text":"[00:16.85] மனசெல்லாம் மகிழ்ச்சி பொங்குதே"}]}}]}
                    """.trimIndent()
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/event-stream")
                        .body(sseBody.toResponseBody("text/event-stream".toMediaType()))
                        .build()
                }
                else -> error("Unexpected model call: $modelId")
            }
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val service = GeminiLyricsService(
            httpClient = client,
            modelResponseTimeoutMs = 15_000L
        )

        val tempAudio = File.createTempFile("tamil_vocal_test", ".mp3").apply {
            writeBytes(ByteArray(128) { 0x1A })
            deleteOnExit()
        }
        val song = Song(
            id = 42L,
            title = "Test Tamil Track",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 210_000L,
            path = tempAudio.absolutePath,
            contentUriString = ""
        )

        val result = service.transcribeAudio(
            audioFile = tempAudio,
            song = song,
            targetLanguage = TranscribeLanguage.TAMIL,
            apiKey = "AIzaSyTestFakeKey123456789012345",
            onStatusUpdate = { statuses.add(it) }
        )

        assertTrue("Expected transcription to succeed on next best model", result.isSuccess)
        assertEquals(listOf("gemini-4-neon", "gemini-4-hydrogen", "gemini-4-argon"), calledModels)
        val (plain, syncedJson) = result.getOrThrow()
        assertTrue("Should preserve Tamil திசை vs இசை distinction", plain.contains("எட்டுத் திசையும் இசை கேட்குதே"))
        assertFalse("Should produce synced JSON", syncedJson.isNullOrBlank())
        assertTrue("Should notify user of 15s switch", statuses.any { it.contains("gemini-4-neon") && it.contains("gemini-4-hydrogen") })
    }

    @Test
    fun testTamilPromptAndDeepVocalRetranscribeInstructions() {
        val service = GeminiLyricsService()
        val song = Song(
            id = 1L,
            title = "En Iniya Thanimaye",
            artist = "Sid Sriram",
            album = "Teddy",
            durationMs = 264_000L,
            path = "/tmp/test.mp3",
            contentUriString = ""
        )

        val initialPrompt = service.buildTranscriptionPrompt(song, TranscribeLanguage.TAMIL, previousAttempt = null)
        assertTrue(initialPrompt.contains("திசை"))
        assertTrue(initialPrompt.contains("இசை"))
        assertTrue(initialPrompt.contains("exact acoustic onset"))

        val retranscribePrompt = service.buildTranscriptionPrompt(
            song = song,
            targetLanguage = TranscribeLanguage.TAMIL,
            previousAttempt = "[00:12.00] எட்டு இசையும்"
        )
        assertTrue(retranscribePrompt.contains("DEEP VOCAL-FOCUS RE-TRANSCRIPTION MODE"))
        assertTrue(retranscribePrompt.contains("DO NOT blindly copy words or timestamps"))
        assertTrue(retranscribePrompt.contains("திசை"))
        assertTrue(retranscribePrompt.contains("இசை"))
    }

    @Test
    fun testNormalizeTranscribedLrcClampsAndRepairsOrder() {
        val service = GeminiLyricsService()
        val raw = """
            ```lrc
            [00:00.00] 🎵
            [00:02.00] ♪
            [00:15.50] முதல் வரி
            [00:14.00] இரண்டாம் வரி
            [04:30.00] கடந்த வரி
            ```
        """.trimIndent()

        val normalized = service.normalizeTranscribedLrc(raw, songDurationMs = 180_000L)
        assertFalse(normalized.contains("🎵"))
        assertFalse(normalized.contains("04:30.00"))
        val lines = normalized.lines()
        assertEquals(3, lines.size)
        assertEquals("[00:00.00] ♪", lines[0])
        assertEquals("[00:15.50] முதல் வரி", lines[1])
        assertEquals("[00:15.50] இரண்டாம் வரி", lines[2])
    }
}
