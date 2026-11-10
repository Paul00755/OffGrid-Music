package com.example.service.transcription

import android.content.Context
import android.util.Base64
import com.example.BuildConfig
import com.example.data.model.Song
import com.example.util.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

class GeminiLyricsService(
    private val context: Context? = null
) {
    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        // Candidate models in preference order (user-specified models with robust fallbacks)
        private val TRANSCRIBE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-3.5-flash",
            "gemini-2.5-flash-native-audio-preview-12-2025"
        )
        private val TRANSLATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-3.5-flash"
        )
        private const val MAX_AUDIO_INLINE_BYTES = 18 * 1024 * 1024 // 18 MB limit for Gemini REST inlineData
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Validates that the provided Gemini API key has the correct format and can
     * authenticate successfully with the Gemini API endpoint.
     */
    suspend fun validateApiKey(apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key cannot be empty."))
        }
        if (trimmed.startsWith("sk-") || trimmed.startsWith("ghp_") || trimmed.startsWith("hf_") || trimmed.startsWith("xoxb-")) {
            return@withContext Result.failure(
                IllegalArgumentException("This API key belongs to another provider. Please enter a valid Google Gemini API key.")
            )
        }
        if (!trimmed.startsWith("AIza") && trimmed.length < 20) {
            return@withContext Result.failure(
                IllegalArgumentException("Invalid Gemini API key format. A valid Gemini key usually starts with 'AIza'.")
            )
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models"
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", trimmed)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val code = response.code
                    val msg = when (code) {
                        400, 403 -> "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                        429 -> "Rate limit reached for your Gemini API key. Please check your account quota."
                        else -> "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                    }
                    Result.failure(SecurityException(msg))
                }
            }
        } catch (e: java.io.IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Translates existing lyrics text while strictly preserving every timestamp [mm:ss.xx].
     * Accurately handles Tanglish to Tamil script and maintains mixed-language lyrics.
     */
    suspend fun translateLyrics(
        existingLrc: String,
        targetLanguage: TranscribeLanguage,
        apiKey: String,
        previousAttempt: String? = null
    ): Result<Pair<String, String?>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                SecurityException("There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound.")
            )
        }

        if (existingLrc.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Source lyrics are empty."))
        }

        val prompt = buildTranslationPrompt(existingLrc, targetLanguage, previousAttempt)
        var lastError: Exception? = null

        for (model in TRANSLATE_MODELS) {
            try {
                val responseText = executeGeminiTextRequest(model, cleanKey, prompt)
                if (responseText.isNotBlank()) {
                    val cleanResponse = cleanLrcTextResponse(responseText)
                    val (plain, syncedJson) = LrcParser.parseLrcContent(cleanResponse)
                    if (!plain.isNullOrBlank() || !syncedJson.isNullOrBlank()) {
                        // Validate and re-align timestamps if necessary
                        val alignedSyncedJson = alignTimestampsWithOriginal(existingLrc, cleanResponse, syncedJson)
                        return@withContext Result.success(Pair(plain ?: cleanResponse, alignedSyncedJson))
                    }
                }
            } catch (e: Exception) {
                lastError = e
                if (e is SecurityException) {
                    break
                }
                // If 404 model not found, loop to next candidate
                if (e.message?.contains("404") != true && e.message?.contains("model") != true) {
                    // Fatal network or auth error, don't waste time on duplicate retries
                    break
                }
            }
        }

        Result.failure(lastError ?: IllegalStateException("Translation returned an empty or invalid result."))
    }

    /**
     * Transcribes vocal content from audio into synchronized LRC timed lyrics.
     * Works with song audio files or recorded microphone audio.
     */
    suspend fun transcribeAudio(
        audioFile: File,
        song: Song?,
        targetLanguage: TranscribeLanguage,
        apiKey: String,
        previousAttempt: String? = null
    ): Result<Pair<String, String?>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                SecurityException("There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound.")
            )
        }

        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("Audio file not found or is empty: ${audioFile.name}")
            )
        }

        val mimeType = detectAudioMimeType(audioFile)
        val audioBase64 = try {
            readAudioAsBase64(audioFile)
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException("Unable to read audio file (${e.message})", e)
            )
        }

        val prompt = buildTranscriptionPrompt(song, targetLanguage, previousAttempt)
        var lastError: Exception? = null

        for (model in TRANSCRIBE_MODELS) {
            try {
                val responseText = executeGeminiAudioRequest(model, cleanKey, prompt, audioBase64, mimeType)
                if (responseText.isNotBlank()) {
                    val cleanResponse = cleanLrcTextResponse(responseText)
                    val (plain, syncedJson) = LrcParser.parseLrcContent(cleanResponse)
                    if (!plain.isNullOrBlank() || !syncedJson.isNullOrBlank()) {
                        return@withContext Result.success(Pair(plain ?: cleanResponse, syncedJson))
                    }
                }
            } catch (e: Exception) {
                lastError = e
                if (e is SecurityException) {
                    break
                }
                if (e.message?.contains("404") != true && e.message?.contains("model") != true) {
                    break
                }
            }
        }

        Result.failure(lastError ?: IllegalStateException("Audio transcription returned an empty result."))
    }

    private fun buildTranslationPrompt(
        lrcText: String,
        targetLanguage: TranscribeLanguage,
        previousAttempt: String? = null
    ): String {
        val correctionBlock = if (!previousAttempt.isNullOrBlank()) {
            """

RE-TRANSCRIBE / CORRECTION MODE:
The user requested a re-transcription/re-translation with corrections because the previous draft had inaccuracies.
Carefully review the Previous Draft below against the Input Lyrics, fix any awkward phrasing, mistranslated words, script errors, or missed Tanglish/multilingual nuances, while strictly keeping every [mm:ss.xx] timestamp intact:
Previous Draft to Improve:
${previousAttempt.take(8000)}
"""
        } else ""

        val languageSpecificRules = when (targetLanguage) {
            TranscribeLanguage.TANGLISH -> """
3. DESTINATION IS TANGLISH (Tamil in English/Latin alphabet + English):
   - Convert any Tamil script (தமிழ்) or other script into natural, readable Romanized Tamil (Tanglish) using the English alphabet (e.g. "உயிரின் உயிரே" -> "Uyirin uyire", "என்னை கொல்லாதே" -> "Ennai kollathey").
   - Keep all English words, phrases, and acronyms in their exact standard English spelling (e.g. "OMG ponnu", "Don't stop the party", "Baby").
   - Do NOT output Tamil Unicode characters (அ-ஔ, க-ன) when Tanglish is selected."""
            TranscribeLanguage.TAMIL -> """
3. DESTINATION IS TAMIL (தமிழ்) WITH CODE-SWITCHED ENGLISH PRESERVATION:
   - If the input contains Tanglish (Tamil words written in Latin/English script), convert the Tamil words into accurate, natural Tamil script (e.g. "Uyirin uyire" -> "உயிரின் உயிரே", "Ennai kollathey" -> "என்னை கொல்லாதே").
   - CRITICAL TANGLISH / ENGLISH RULE: Whenever a word, acronym, or phrase in the song is an English word or acronym (for example "OMG" in "OMG Ponnu" -> "OMG பொண்ணு", or words like "Baby", "Love", "Party", "Sorry", "DJ", "Girl"), KEEP the English word/acronym in English Latin script ("OMG", NOT "ஓஎம்ஜி" or "ஓ.எம்.ஜி"). Never transliterate English words or acronyms into Tamil letters."""
            TranscribeLanguage.ENGLISH -> """
3. DESTINATION IS ENGLISH:
   - Translate non-English or Tanglish lyrics into natural, singable English lyric lines while keeping original English phrases and acronyms (like "OMG") intact."""
            else -> """
3. DESTINATION IS ${targetLanguage.englishName.uppercase()} (${targetLanguage.nativeName}):
   - Output the lyrics in accurate ${targetLanguage.englishName} script (${targetLanguage.nativeName}), while keeping genuinely sung English acronyms and words (like "OMG", "Baby", "DJ") in English Latin letters rather than phonetic transliteration."""
        }

        return """
You are an expert multilingual music lyricist and translator.
Translate/transliterate the following synchronized lyrics into ${targetLanguage.englishName} (${targetLanguage.nativeName}).

CRITICAL REQUIREMENTS:
1. STRICTLY PRESERVE EVERY TIMESTAMP [mm:ss.xx] EXACTLY AS IS on every line. Do NOT change, recompute, shift, round, or drop any timestamp tag.
2. The number of lines and the timestamps associated with each line must match the input line-for-line.
$languageSpecificRules
4. Preserve non-vocal instrumental passages with a timestamp and the single musical note symbol ♪ (NEVER use 🎼, 🎵, or 🎶). Do not add markers where vocals are present or invent musical breaks.
5. Use natural punctuation that matches the sung phrasing and emotion, including commas, periods, apostrophes, question marks, exclamation marks, and ellipses when appropriate. Do not change, omit, or embellish the words to force punctuation.
6. Return ONLY the raw LRC text lines. Do NOT include markdown code blocks (no ```lrc or ```), no intro, and no explanations.$correctionBlock

Input Lyrics:
$lrcText
""".trimIndent()
    }

    private fun buildTranscriptionPrompt(
        song: Song?,
        targetLanguage: TranscribeLanguage,
        previousAttempt: String? = null
    ): String {
        val songContext = if (song != null) {
            buildString {
                append("Track: \"${song.displayTitle}\" by \"${song.displayArtist}\"")
                if (song.displayAlbum.isNotBlank() && !song.displayAlbum.equals("Unknown Album", ignoreCase = true)) {
                    append(" (Album/Movie: \"${song.displayAlbum}\")")
                }
            }
        } else {
            "Audio Recording"
        }

        val correctionBlock = if (!previousAttempt.isNullOrBlank()) {
            """

RE-TRANSCRIBE WITH CORRECTIONS:
The user requested a fresh re-transcription to fix errors in the previous attempt.
Listen closely to the audio again and compare it with the Previous Attempt below. Correct any misheard words, spelling/script mistakes, English words wrongly written in native script, missing vocal lines, or inaccurate [mm:ss.xx] timestamps so the lyrics and timing are significantly more accurate:
Previous Attempt to Correct:
${previousAttempt.take(8000)}
"""
        } else ""

        val languageInstruction = when (targetLanguage) {
            TranscribeLanguage.TANGLISH -> """
3. DESTINATION LANGUAGE IS TANGLISH (Romanized Tamil in English letters + English):
   - Transcribe all Tamil sung words phonetically in natural, conversational Romanized Tamil using the English alphabet (e.g., "OMG ponnu", "Uyirin uyire", "Ennai kollathey", "Vaathi coming").
   - Keep all English sung words, hooks, and acronyms in standard English spelling (e.g., "OMG", "Baby", "Love", "Party").
   - Do NOT use Tamil Unicode script (தமிழ்) when Tanglish is selected."""
            TranscribeLanguage.TAMIL -> """
3. DESTINATION LANGUAGE IS TAMIL (தமிழ்) WITH ENGLISH WORD PRESERVATION:
   - Write Tamil vocal words in accurate, natural Tamil script (தமிழ்).
   - CRITICAL TANGLISH / ENGLISH RULE: Indian and Tamil songs frequently mix English words, acronyms, and hooks with Tamil vocals (e.g., "OMG Ponnu" from Sarkar). Whenever the singer sings an English word, acronym, or phrase (such as "OMG", "Baby", "Love", "Sorry", "Party", "Single", "Breakup", "Queen", "King", "DJ"), you MUST keep that English word/acronym in English Latin letters (e.g., "OMG பொண்ணு", NOT "ஓஎம்ஜி பொண்ணு"). NEVER transliterate English words or acronyms into Tamil letters."""
            TranscribeLanguage.ENGLISH -> """
3. DESTINATION LANGUAGE IS ENGLISH:
   - If the vocals are in English, transcribe the exact sung English words.
   - If the vocals are in another language, provide accurate synchronized English lyrics while keeping sung English hooks/acronyms (like "OMG") intact."""
            else -> """
3. DESTINATION LANGUAGE IS ${targetLanguage.englishName.uppercase()} (${targetLanguage.nativeName}):
   - Transcribe the sung vocals into accurate ${targetLanguage.englishName} script (${targetLanguage.nativeName}).
   - Whenever the singer sings an English word, acronym, or hook (such as "OMG", "Baby", "Love", "Party", "DJ"), keep that English word/acronym in English Latin letters rather than transliterating it into ${targetLanguage.nativeName} script."""
        }

        return """
You are an elite multilingual studio vocal transcription and LRC synchronization engine.
Listen carefully to the entire vocal performance in this audio ($songContext) and transcribe every sung line into synchronized LRC format.

CRITICAL REQUIREMENTS:
1. VOCAL FOCUS & COMPLETENESS:
   - Isolate and transcribe lead vocals, duet/chorus vocals, rap verses, and distinct vocal hooks across the full track from start to finish, even during dense percussion or background instrumentation.
   - Use the track metadata ($songContext) as context to accurately recognize the song's signature hook phrases, proper nouns, and multilingual expressions.
2. EXACT SYNCHRONIZATION:
   - Every line MUST start with an accurate timestamp in standard format: [mm:ss.xx] Lyric line
   - Align each timestamp to the exact onset of the vocal phrase. Do NOT space timestamps at artificial fixed intervals.
$languageInstruction
4. INSTRUMENTAL BREAKS SYMBOL:
   - For clearly audible instrumental intros, interludes, or outros with no sung words, output a timestamp followed ONLY by the musical note character: ♪ (for example: [00:00.00] ♪).
   - NEVER use the 🎼, 🎵, or 🎶 emojis anywhere in the output. Do not insert ♪ over sung vocals.
5. NATURAL PUNCTUATION:
   - Use natural punctuation matching the sung phrasing and emotion (commas, periods, apostrophes, question marks, exclamation marks, or ellipses when appropriate) without altering the sung words.
6. Return ONLY the standard LRC formatted lyrics lines. Do NOT include markdown wrappers (no ```lrc), explanations, or notes.$correctionBlock
""".trimIndent()
    }

    private fun cleanLrcTextResponse(raw: String): String {
        var clean = raw.trim()
        if (clean.startsWith("```")) {
            val lines = clean.lines()
            val filtered = lines.filterNot { it.trim().startsWith("```") }
            clean = filtered.joinToString("\n").trim()
        }
        // Replace any 🎼, 🎵, or 🎶 emojis with the bold musical note symbol ♪
        clean = clean
            .replace("🎼", "♪")
            .replace("🎵", "♪")
            .replace("🎶", "♪")
        return clean
    }

    /**
     * If Gemini altered timestamps slightly, align the translated text back to the original timestamps.
     */
    private fun alignTimestampsWithOriginal(
        originalLrc: String,
        translatedLrc: String,
        translatedSyncedJson: String?
    ): String? {
        val originalLines = LrcParser.parseLrcToLines(originalLrc)
        val translatedLines = LrcParser.parseLrcToLines(translatedLrc)

        if (originalLines.isNotEmpty() && translatedLines.isNotEmpty() && originalLines.size == translatedLines.size) {
            // Perfect 1:1 match -> pair translated text with exact original timestamp
            val jsonArray = JSONArray()
            for (i in originalLines.indices) {
                val obj = JSONObject()
                obj.put("timeMs", originalLines[i].timeMs)
                obj.put("text", translatedLines[i].text)
                jsonArray.put(obj)
            }
            return jsonArray.toString()
        }
        return translatedSyncedJson
    }

    private fun executeGeminiTextRequest(model: String, apiKey: String, prompt: String): String {
        val url = "$BASE_URL$model:generateContent"
        val requestJson = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("topP", 0.95)
            })
        }

        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 400 || response.code == 403) {
                    throw SecurityException("There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound.")
                }
                val errBody = response.body?.string() ?: ""
                throw IllegalStateException("Gemini API error (HTTP ${response.code}): $errBody")
            }
            val respString = response.body?.string() ?: ""
            return extractTextFromGeminiResponse(respString)
        }
    }

    private fun executeGeminiAudioRequest(
        model: String,
        apiKey: String,
        prompt: String,
        audioBase64: String,
        mimeType: String
    ): String {
        val url = "$BASE_URL$model:generateContent"
        val requestJson = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", mimeType)
                                put("data", audioBase64)
                            })
                        })
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("topP", 0.9)
            })
        }

        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 400 || response.code == 403) {
                    throw SecurityException("There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound.")
                }
                val errBody = response.body?.string() ?: ""
                throw IllegalStateException("Gemini audio transcription error (HTTP ${response.code}): $errBody")
            }
            val respString = response.body?.string() ?: ""
            return extractTextFromGeminiResponse(respString)
        }
    }

    private fun extractTextFromGeminiResponse(jsonString: String): String {
        val json = JSONObject(jsonString)
        val candidates = json.optJSONArray("candidates") ?: return ""
        val firstCandidate = candidates.optJSONObject(0) ?: return ""
        val content = firstCandidate.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i)
            val text = part?.optString("text", "") ?: ""
            if (text.isNotEmpty()) {
                sb.append(text)
            }
        }
        return sb.toString()
    }

    private fun detectAudioMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "mp3" -> "audio/mp3"
            "m4a", "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            else -> "audio/mp3"
        }
    }

    private fun readAudioAsBase64(file: File): String {
        val fileSize = file.length()
        val readBytes = if (fileSize > MAX_AUDIO_INLINE_BYTES) {
            // Read first MAX_AUDIO_INLINE_BYTES for huge files to prevent OOM
            val buffer = ByteArray(MAX_AUDIO_INLINE_BYTES)
            FileInputStream(file).use { input ->
                val bytesRead = input.read(buffer)
                if (bytesRead < MAX_AUDIO_INLINE_BYTES) {
                    buffer.copyOf(bytesRead)
                } else {
                    buffer
                }
            }
        } else {
            file.readBytes()
        }
        return Base64.encodeToString(readBytes, Base64.NO_WRAP)
    }
}
