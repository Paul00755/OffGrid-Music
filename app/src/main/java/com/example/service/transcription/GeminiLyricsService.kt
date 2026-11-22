package com.example.service.transcription

import android.content.Context
import android.util.Base64
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
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GeminiLyricsService(
    private val context: Context? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = BASE_URL,
    private val modelResponseTimeoutMs: Long = MODEL_RESPONSE_TIMEOUT_MS,
    private val finalFallbackReadTimeoutMs: Long = FINAL_FALLBACK_READ_TIMEOUT_MS
) {
    companion object {
        internal const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        internal const val MODEL_RESPONSE_TIMEOUT_MS = 15_000L
        internal const val FINAL_FALLBACK_READ_TIMEOUT_MS = 45_000L
        private const val MODEL_DISCOVERY_TIMEOUT_MS = 6_000L
        private const val MODEL_CACHE_TTL_MS = 15 * 60_000L
        private const val MAX_CANDIDATE_MODELS = 8

        /**
         * Curated highest-to-next-best audio-capable Gemini fallback ladder
         * (used if /v1beta/models discovery is temporarily unreachable).
         */
        internal val FALLBACK_AUDIO_MODELS = listOf(
            "gemini-4-neon",
            "gemini-4-hydrogen",
            "gemini-4-argon",
            "gemini-3.8-flash",
            "gemini-3.5-pro",
            "gemini-3.5-flash",
            "gemini-3.0-pro",
            "gemini-3.0-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.0-flash"
        )

        private const val MAX_AUDIO_INLINE_BYTES = 18 * 1024 * 1024 // 18 MB limit for Gemini REST inlineData

        private val EXCLUDED_MODEL_TOKENS = listOf(
            "embedding",
            "aqa",
            "imagen",
            "veo",
            "tts",
            "image",
            "robotics",
            "computer-use",
            "nano",
            "gemma",
            "learnlm",
            "bison",
            "gecko",
            "live",
            "native-audio",
            "vision",
            "text-"
        )

        private val VERSION_REGEX = Regex("""gemini-(\d+)(?:\.(\d+))?""")

        private data class CachedModelList(
            val models: List<String>,
            val fetchedAtMs: Long
        )

        private val discoveredModelCache = ConcurrentHashMap<Int, CachedModelList>()

        internal fun clearModelCacheForTesting() {
            discoveredModelCache.clear()
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(MODEL_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Returns true only for Gemini multimodal models that support REST `generateContent`
         * and audio/Tamil vocal transcription (filtering out embedding, image, TTS, video,
         * WebSocket-only live audio, and legacy 1.0 text-only models).
         */
        internal fun isAudioTranscriptionCapableModel(
            modelId: String,
            supportedMethods: Set<String> = setOf("generateContent")
        ): Boolean {
            val cleanId = modelId.removePrefix("models/").trim().lowercase(Locale.US)
            if (!cleanId.startsWith("gemini-")) return false
            if (supportedMethods.isNotEmpty() && !supportedMethods.contains("generateContent")) {
                return false
            }
            if (EXCLUDED_MODEL_TOKENS.any { cleanId.contains(it) }) {
                return false
            }
            val match = VERSION_REGEX.find(cleanId) ?: return false
            val major = match.groupValues[1].toIntOrNull() ?: return false
            val minor = match.groupValues[2].toIntOrNull() ?: 0
            // Gemini 1.0 does not support native audio transcription; 1.5+ (2.x, 3.x, 4.x) do.
            return major >= 2 || (major == 1 && minor >= 5)
        }

        /**
         * Scores audio-capable Gemini models from highest capability to next-best fallback:
         * Generation version first (4.x > 3.8 > 3.5 > 3.0 > 2.5 > 2.0 > 1.5),
         * then capability tier (neon/ultra > hydrogen > pro > argon > flash > flash-lite).
         */
        internal fun rankModelScore(modelId: String): Int {
            val cleanId = modelId.removePrefix("models/").trim().lowercase(Locale.US)
            val match = VERSION_REGEX.find(cleanId)
            val major = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val minor = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            val versionScore = major * 10_000 + minor * 1_000

            val tierScore = when {
                cleanId.contains("ultra") || cleanId.contains("neon") -> 950
                cleanId.contains("hydrogen") -> 900
                cleanId.contains("pro") -> 850
                cleanId.contains("argon") -> 800
                cleanId.contains("flash-lite") || cleanId.contains("lite") || cleanId.contains("-8b") -> 400
                cleanId.contains("flash") -> 650
                else -> 500
            }

            var variantAdjustment = 0
            if (cleanId.endsWith("-latest")) variantAdjustment += 20
            if (cleanId.contains("preview") || cleanId.contains("exp")) variantAdjustment -= 15
            if (cleanId.contains("thinking")) variantAdjustment -= 5
            // Prefer canonical base aliases (e.g., gemini-2.5-flash) over dated snapshots (-001, -05-20)
            if (Regex("""-\d{3}$|-\d{2}-\d{2}$|-\d{2}-\d{4}$""").containsMatchIn(cleanId)) {
                variantAdjustment -= 8
            }

            return versionScore + tierScore + variantAdjustment
        }

        /**
         * Sorts and deduplicates discovered audio-capable Gemini models so we always try the
         * highest-capability model supported by the user's API key first, stepping smoothly down
         * distinct tiers (e.g., neon -> hydrogen -> argon -> 3.8-flash -> 3.5-pro -> 2.5-pro -> 2.5-flash).
         */
        internal fun rankAndSelectModels(rawModels: List<Pair<String, Set<String>>>): List<String> {
            val capable = rawModels
                .filter { (id, methods) -> isAudioTranscriptionCapableModel(id, methods) }
                .map { (id, _) -> id.removePrefix("models/").trim() }
                .distinct()
                .sortedWith(
                    compareByDescending<String> { rankModelScore(it) }
                        .thenBy { it.length }
                )

            // Deduplicate redundant dated snapshots of the exact same base model family
            val seenFamilies = LinkedHashSet<String>()
            val selected = mutableListOf<String>()
            for (model in capable) {
                val familyKey = model.lowercase(Locale.US)
                    .replace(Regex("""-(preview|exp)(-[\w.-]+)?$"""), "")
                    .replace(Regex("""-(\d{3}|latest|\d{2}-\d{2}|\d{2}-\d{4})$"""), "")
                if (seenFamilies.add(familyKey)) {
                    selected.add(model)
                    if (selected.size >= MAX_CANDIDATE_MODELS) break
                }
            }
            return selected
        }
    }

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
            val url = modelsListUrl()
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", trimmed)
                .get()
                .build()

            val discoveryClient = httpClient.newBuilder()
                .readTimeout(MODEL_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            discoveryClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val ranked = parseAndRankModelsResponse(bodyStr)
                    if (ranked.isNotEmpty()) {
                        discoveredModelCache[trimmed.hashCode()] = CachedModelList(ranked, System.currentTimeMillis())
                    }
                    Result.success(Unit)
                } else {
                    val code = response.code
                    val msg = when (code) {
                        400, 401, 403 -> "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
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
     * Queries `/v1beta/models` for the user's API key (cached 15 min) and returns the ranked
     * highest-to-next-best audio-capable Gemini models supported by that key.
     */
    internal fun resolveSupportedModels(apiKey: String): List<String> {
        val cacheKey = apiKey.hashCode()
        val now = System.currentTimeMillis()
        discoveredModelCache[cacheKey]?.let { cached ->
            if ((now - cached.fetchedAtMs) < MODEL_CACHE_TTL_MS && cached.models.isNotEmpty()) {
                return cached.models
            }
        }

        try {
            val discoveryClient = httpClient.newBuilder()
                .connectTimeout(MODEL_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(MODEL_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url("${modelsListUrl()}?pageSize=200")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()

            discoveryClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val ranked = parseAndRankModelsResponse(bodyStr)
                    if (ranked.isNotEmpty()) {
                        discoveredModelCache[cacheKey] = CachedModelList(ranked, now)
                        return ranked
                    }
                } else if (response.code == 401 || response.code == 403 ||
                    (response.code == 400 && isInvalidApiKeyError(response.body?.string().orEmpty()))
                ) {
                    throw SecurityException(
                        "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                    )
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            // Fall through to curated highest-to-next-best ladder if discovery network call fails
        }

        return FALLBACK_AUDIO_MODELS
    }

    private fun modelsListUrl(): String = baseUrl.trimEnd('/')

    private fun parseAndRankModelsResponse(jsonString: String): List<String> {
        if (jsonString.isBlank()) return emptyList()
        return try {
            val root = JSONObject(jsonString)
            val arr = root.optJSONArray("models") ?: return emptyList()
            val discovered = mutableListOf<Pair<String, Set<String>>>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isBlank()) continue
                val methodsArr = obj.optJSONArray("supportedGenerationMethods")
                val methods = mutableSetOf<String>()
                if (methodsArr != null) {
                    for (m in 0 until methodsArr.length()) {
                        val methodName = methodsArr.optString(m, "").trim()
                        if (methodName.isNotEmpty()) methods.add(methodName)
                    }
                }
                discovered.add(name to methods)
            }
            rankAndSelectModels(discovered)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Translates existing lyrics text while strictly preserving every timestamp [mm:ss.xx].
     * Uses the highest supported Gemini model with automatic 15-second fallback to the next best model.
     */
    suspend fun translateLyrics(
        existingLrc: String,
        targetLanguage: TranscribeLanguage,
        apiKey: String,
        previousAttempt: String? = null,
        onStatusUpdate: ((String) -> Unit)? = null
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

        val models = try {
            onStatusUpdate?.invoke("Selecting best Gemini model...")
            resolveSupportedModels(cleanKey)
        } catch (e: SecurityException) {
            return@withContext Result.failure(e)
        }

        val isCorrectionPass = !previousAttempt.isNullOrBlank()
        val prompt = buildTranslationPrompt(existingLrc, targetLanguage, previousAttempt)
        var lastError: Exception? = null

        for ((index, model) in models.withIndex()) {
            val isLastModel = index == models.lastIndex
            val timeoutMs = if (isLastModel) finalFallbackReadTimeoutMs else modelResponseTimeoutMs
            try {
                onStatusUpdate?.invoke(
                    if (isCorrectionPass) "Refining lyrics with $model..." else "Translating lyrics with $model..."
                )
                val responseText = executeGeminiTextRequest(
                    model = model,
                    apiKey = cleanKey,
                    prompt = prompt,
                    isCorrectionPass = isCorrectionPass,
                    responseTimeoutMs = timeoutMs
                )
                if (responseText.isNotBlank()) {
                    val cleanResponse = cleanLrcTextResponse(responseText)
                    val (plain, syncedJson) = LrcParser.parseLrcContent(cleanResponse)
                    if (!plain.isNullOrBlank() || !syncedJson.isNullOrBlank()) {
                        val alignedSyncedJson = alignTimestampsWithOriginal(existingLrc, cleanResponse, syncedJson)
                        return@withContext Result.success(Pair(plain ?: cleanResponse, alignedSyncedJson))
                    }
                }
            } catch (e: SecurityException) {
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                lastError = e
                val nextModel = models.getOrNull(index + 1)
                if (nextModel != null && shouldFallbackToNextModel(e)) {
                    val reason = if (isTimeoutException(e)) "took >${modelResponseTimeoutMs / 1000}s" else "unavailable"
                    onStatusUpdate?.invoke("$model $reason — switching to $nextModel...")
                    continue
                }
                break
            }
        }

        Result.failure(lastError ?: IllegalStateException("Translation returned an empty or invalid result."))
    }

    /**
     * Transcribes vocal content from audio into synchronized LRC timed lyrics.
     * Dynamically picks the highest audio-capable Gemini model supported by the user's API key,
     * and automatically switches to the next best model if a model takes longer than 15 seconds to respond.
     */
    suspend fun transcribeAudio(
        audioFile: File,
        song: Song?,
        targetLanguage: TranscribeLanguage,
        apiKey: String,
        previousAttempt: String? = null,
        onStatusUpdate: ((String) -> Unit)? = null
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

        val models = try {
            onStatusUpdate?.invoke("Selecting best vocal transcription model...")
            resolveSupportedModels(cleanKey)
        } catch (e: SecurityException) {
            return@withContext Result.failure(e)
        }

        val mimeType = detectAudioMimeType(audioFile)
        val audioBase64 = try {
            readAudioAsBase64(audioFile)
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException("Unable to read audio file (${e.message})", e)
            )
        }

        val isCorrectionPass = !previousAttempt.isNullOrBlank()
        val prompt = buildTranscriptionPrompt(song, targetLanguage, previousAttempt)
        var lastError: Exception? = null

        for ((index, model) in models.withIndex()) {
            val isLastModel = index == models.lastIndex
            val timeoutMs = if (isLastModel) finalFallbackReadTimeoutMs else modelResponseTimeoutMs
            try {
                onStatusUpdate?.invoke(
                    if (isCorrectionPass) {
                        "Deep vocal re-transcription with $model..."
                    } else {
                        "Transcribing vocals with $model..."
                    }
                )
                val responseText = executeGeminiAudioRequest(
                    model = model,
                    apiKey = cleanKey,
                    prompt = prompt,
                    audioBase64 = audioBase64,
                    mimeType = mimeType,
                    isCorrectionPass = isCorrectionPass,
                    responseTimeoutMs = timeoutMs
                )
                if (responseText.isNotBlank()) {
                    val normalizedLrc = normalizeTranscribedLrc(
                        rawResponse = responseText,
                        songDurationMs = song?.durationMs ?: 0L
                    )
                    val (plain, syncedJson) = LrcParser.parseLrcContent(normalizedLrc)
                    if (!plain.isNullOrBlank() || !syncedJson.isNullOrBlank()) {
                        return@withContext Result.success(Pair(plain ?: normalizedLrc, syncedJson))
                    }
                }
            } catch (e: SecurityException) {
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                lastError = e
                val nextModel = models.getOrNull(index + 1)
                if (nextModel != null && shouldFallbackToNextModel(e)) {
                    val reason = if (isTimeoutException(e)) "took >${modelResponseTimeoutMs / 1000}s" else "unavailable"
                    onStatusUpdate?.invoke("$model $reason — switching to $nextModel...")
                    continue
                }
                break
            }
        }

        Result.failure(lastError ?: IllegalStateException("Audio transcription returned an empty result."))
    }

    private fun isTimeoutException(e: Throwable): Boolean {
        return e is SocketTimeoutException ||
            e is InterruptedIOException ||
            e.cause is SocketTimeoutException ||
            e.message?.contains("timeout", ignoreCase = true) == true
    }

    private fun isInvalidApiKeyError(errBody: String): Boolean {
        val lower = errBody.lowercase(Locale.US)
        return lower.contains("api_key_invalid") ||
            lower.contains("api key not valid") ||
            lower.contains("invalid api key") ||
            lower.contains("permission_denied") ||
            lower.contains("unauthenticated")
    }

    private fun shouldFallbackToNextModel(e: Exception): Boolean {
        if (e is SecurityException) return false
        if (isTimeoutException(e)) return true
        val msg = e.message.orEmpty()
        return msg.contains("HTTP 404") ||
            msg.contains("HTTP 429") ||
            msg.contains("HTTP 500") ||
            msg.contains("HTTP 502") ||
            msg.contains("HTTP 503") ||
            msg.contains("HTTP 504") ||
            msg.contains("HTTP 400") ||
            msg.contains("not found", ignoreCase = true) ||
            msg.contains("not supported", ignoreCase = true) ||
            msg.contains("overloaded", ignoreCase = true) ||
            msg.contains("model", ignoreCase = true)
    }

    private fun buildTranslationPrompt(
        lrcText: String,
        targetLanguage: TranscribeLanguage,
        previousAttempt: String? = null
    ): String {
        val correctionBlock = if (!previousAttempt.isNullOrBlank()) {
            """

RE-TRANSLATE / DEEP CORRECTION AUDIT MODE:
The user explicitly requested a re-translation with correction because the previous draft had inaccuracies in letters, words, or phrasing.
Perform a strict line-by-line phonetic and semantic audit comparing the Previous Draft against the Input Lyrics:
1. Check every single word and syllable for Tamil/Indian phonetic accuracy: verify word-initial consonants vs vowels (e.g., "திசை" vs "இசை", "தலை" vs "கலை" vs "அலை", "தேன்" vs "ஏன்"), short vs. long vowels (குறில் vs. நெடில்: அ/ஆ, இ/ஈ, உ/ஊ, எ/ஏ, ஒ/ஓ), retroflex vs. dental consonants (ல/ள/ழ, ன/ண/ந, ர/ற), and proper Pulli (்) placement.
2. Verify that colloquial/spoken Tamil forms (பேச்சுத் தமிழ், e.g., "வாரேன்", "போறேன்", "கண்ணு", "பொண்ணு") match the actual sung words rather than stiff textbook translations or hallucinated words.
3. Ensure all English words and acronyms (such as "OMG", "Baby", "Love", "Party", "DJ", "Single") are kept in Latin script and NOT transliterated into Tamil letters.
4. Keep every [mm:ss.xx] timestamp 100% identical to the Input Lyrics.
Previous Draft to Audit & Correct:
${previousAttempt.take(8000)}
"""
        } else ""

        val languageSpecificRules = when (targetLanguage) {
            TranscribeLanguage.TANGLISH -> """
3. DESTINATION IS TANGLISH (Tamil in English/Latin alphabet + English):
   - Convert any Tamil script (தமிழ்) or other script into natural, accurate Romanized Tamil (Tanglish) using the English alphabet (e.g. "உயிரின் உயிரே" -> "Uyirin uyire", "என்னை கொல்லாதே" -> "Ennai kollathey").
   - Distinguish minimal pairs and vowel lengths clearly in Romanization (e.g. "Thisai" vs "Isai", "Kanna" vs "Kaana", "Mazhai" vs "Malai", "Vazhi" vs "Vali").
   - Keep all English words, phrases, and acronyms in their exact standard English spelling (e.g. "OMG ponnu", "Don't stop the party", "Baby").
   - Do NOT output Tamil Unicode characters (அ-ஔ, க-ன) when Tanglish is selected."""
            TranscribeLanguage.TAMIL -> """
3. DESTINATION IS TAMIL (தமிழ்) WITH HIGH-PRECISION ORTHOGRAPHY & CODE-SWITCHED ENGLISH PRESERVATION:
   - Convert Tanglish (Tamil words written in Latin/English script) or source lines into accurate, natural Tamil script (தமிழ்) matching the exact sung Tamil words (e.g. "Uyirin uyire" -> "உயிரின் உயிரே", "Ennai kollathey" -> "என்னை கொல்லாதே").
   - TAMIL LETTER & MINIMAL-PAIR PRECISION: Pay strict attention to word-initial consonants vs bare vowels ("திசை" vs "இசை", "தலை" vs "அலை"), short vs. long vowels (குறில்/நெடில்), consonant distinctions (ல/ள/ழ, ன/ண/ந, ர/ற), Pulli (மெய்யெழுத்து ்), and natural poetic/colloquial Tamil phrasing (e.g. "பொண்ணு", "கண்ணே", "தள்ளிப் போகாதே").
   - CRITICAL TANGLISH / ENGLISH RULE: Whenever a word, acronym, or phrase in the song is an English word or acronym (for example "OMG" in "OMG Ponnu" -> "OMG பொண்ணு", or words like "Baby", "Love", "Party", "Sorry", "DJ", "Girl"), KEEP the English word/acronym in English Latin script ("OMG", NOT "ஓஎம்ஜி" or "ஓ.எம்.ஜி"). Never transliterate English words or acronyms into Tamil letters."""
            TranscribeLanguage.ENGLISH -> """
3. DESTINATION IS ENGLISH:
   - Translate non-English or Tanglish lyrics into natural, singable English lyric lines while keeping original English phrases and acronyms (like "OMG") intact."""
            else -> """
3. DESTINATION IS ${targetLanguage.englishName.uppercase()} (${targetLanguage.nativeName}):
   - Output the lyrics in accurate ${targetLanguage.englishName} script (${targetLanguage.nativeName}), while keeping genuinely sung English acronyms and words (like "OMG", "Baby", "DJ") in English Latin letters rather than phonetic transliteration."""
        }

        return """
You are an expert multilingual music lyricist, Tamil phonetics specialist, and LRC translator.
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

    internal fun buildTranscriptionPrompt(
        song: Song?,
        targetLanguage: TranscribeLanguage,
        previousAttempt: String? = null
    ): String {
        val durationContext = if (song != null && song.durationMs > 0L) {
            val totalSec = song.durationMs / 1000L
            val min = totalSec / 60L
            val sec = totalSec % 60L
            String.format(Locale.US, "%02d:%02d (%d ms)", min, sec, song.durationMs)
        } else null

        val songContext = if (song != null) {
            buildString {
                append("Track: \"${song.displayTitle}\" by \"${song.displayArtist}\"")
                if (song.displayAlbum.isNotBlank() && !song.displayAlbum.equals("Unknown Album", ignoreCase = true)) {
                    append(" (Album/Movie: \"${song.displayAlbum}\")")
                }
                if (durationContext != null) {
                    append(", Total Track Duration: $durationContext")
                }
            }
        } else {
            "Audio Recording"
        }

        val correctionBlock = if (!previousAttempt.isNullOrBlank()) {
            """

DEEP VOCAL-FOCUS RE-TRANSCRIPTION MODE (OVERRIDE FLAWED DRAFT):
The user explicitly tapped "Re-transcribe with Vocals" because the previous attempt had vocal mishearings, Tamil phonetic confusions, or out-of-sync timestamps.
DO NOT blindly copy words or timestamps from the previous draft! Instead, follow this strict vocal-first forensic pass:
1. LISTEN TO THE RAW ISOLATED VOCALS FRESH FROM 00:00.00 TO END: Focus intensely on the singer's voice frequency band (lead vocals, soft whispers, fast rapid-fire syllables, high notes, duet harmonies, and call-and-response lines), cutting through drums, bass, reverb, and synth pads.
2. FORENSIC MINIMAL-PAIR & CONSONANT-ATTACK AUDIT: Re-examine every Tamil word in the audio for subtle phonetic mistakes that occur during singing:
   - Word-initial dental/plosive consonant vs bare vowel onset: e.g., verify whether the singer actually sings "திசை" (thisai = direction, with soft dental 'த்' attack) vs "இசை" (isai = music, pure vowel 'இ' onset), "தலை" vs "கலை" vs "அலை", "தேன்" vs "ஏன்", "காதல்" vs "ஆதல்", "பார்வை" vs "ஆர்வை".
   - Lateral/retroflex/nasal/rhotic contrasts: ல / ள / ழ (வலி vs வழி, மலை vs மழை, அலை vs அழை), ந / ன / ண (மனம் vs மணம், கண் vs கன்), ர / ற (கரை vs கறை), and short vs long vowels (குறில்/நெடில்: படம் vs பாடம், கண்ணே vs காணே, உயிர் vs உயர், நிலா vs நிலை).
   - Cross-check every disputed word against BOTH the exact acoustic consonant heard in the vocal track AND the Tamil poetic meaning/rhyme (எதுகை & மோனை) of the surrounding lines.
3. RE-ANCHOR EVERY TIMESTAMP TO EXACT VOCAL ONSET: Do NOT reuse drifted timestamps from the previous draft. Measure the true audio time when the vocalist's first syllable actually begins on each line (within ±0.05s), fixing early triggers, late lag, cumulative drift after BGM interludes, and skipped repeat hooks.
4. COMPLETE COVERAGE & CLEAN PHRASING: Ensure zero missed pallavi, anupallavi, charanam, rap, or ending lines, and keep genuine English words/acronyms (like "OMG", "Baby", "Love") in English Latin script.

Flawed Previous Draft (for reference of what failed — audit and replace every inaccurate word or timestamp):
${previousAttempt.take(8000)}
"""
        } else ""

        val languageInstruction = when (targetLanguage) {
            TranscribeLanguage.TANGLISH -> """
3. DESTINATION LANGUAGE IS TANGLISH (Romanized Tamil in English letters + English):
   - Transcribe all Tamil sung words phonetically in natural, accurate Romanized Tamil using the English alphabet (e.g., "OMG ponnu", "Uyirin uyire", "Ennai kollathey", "Vaathi coming", "Thalli pogathey").
   - Distinguish phonetically close Tamil words clearly in Romanization so meaning is never confused (e.g., "thisai" vs "isai", "mazhai" vs "malai", "vazhi" vs "vali", "kanna" vs "kaana", "manam" vs "manam", "azhagiye").
   - Keep all English sung words, hooks, and acronyms in standard English spelling (e.g., "OMG", "Baby", "Love", "Party").
   - Do NOT use Tamil Unicode script (தமிழ்) when Tanglish is selected."""
            TranscribeLanguage.TAMIL -> """
3. DESTINATION LANGUAGE IS TAMIL (தமிழ்) WITH STUDIO-GRADE VOCAL PHONETIC PRECISION:
   - Write Tamil vocal words in accurate, natural Tamil script (தமிழ்).
   - CRITICAL TAMIL VOCAL & MINIMAL-PAIR DISAMBIGUATION (PAY EXTRA ATTENTION):
     * Word-initial consonant vs vowel onset: Singing with reverb or legato gliding often softens initial stops. Listen closely for the subtle dental/velar/labial consonant onset AND verify semantic context so near-homophones are NEVER swapped—for example:
       - "திசை" (thisai = direction) vs "இசை" (isai = music)
       - "தலை" (head) vs "கலை" (art) vs "அலை" (wave)
       - "தேன்" (honey) vs "ஏன்" (why)
       - "மழை" (rain) vs "மலை" (mountain)
       - "வழி" (path) vs "வலி" (pain)
       - "மனம்" (mind/heart) vs "மணம்" (fragrance)
       - "கரை" (shore) vs "கறை" (stain)
       - "நிலா" (moon) vs "நிலை" (state)
       - "உயிர்" (life) vs "உயர்" (high)
     * Vowel length precision (குறில் vs. நெடில்: அ/ஆ, இ/ஈ, உ/ஊ, எ/ஏ, ஒ/ஓ): accurately capture sung vowel duration (e.g., "கண்ணே" vs "காணே", "படம்" vs "பாடம்", "அவள்" vs "ஆவள்").
     * Strict consonant accuracy: distinguish retroflex, palatal, and alveolar sounds (ல / ள / ழ, ன / ண / ந, ர / ற) and include proper Pulli (்) on pure consonants.
     * Sandhi & poetic coherence: separate words cleanly across sung legato joins (புணர்ச்சி) and use Tamil poetic context (எதுகை/மோனை rhyme & meaning) to confirm ambiguous syllables.
     * Preserve both literary Tamil (செந்தமிழ்) and authentic spoken cinema Tamil (பேச்சுத் தமிழ், e.g., "பொண்ணு", "கண்ணு", "வாடா", "போடா", "சுத்துதே", "வாரேன்") exactly as sung by the vocalist.
   - CRITICAL TANGLISH / ENGLISH RULE: Whenever the singer sings an English word, acronym, or hook (such as "OMG", "Baby", "Love", "Sorry", "Party", "Single", "Breakup", "Queen", "King", "DJ"), KEEP it in English Latin letters (e.g., "OMG பொண்ணு", NOT "ஓஎம்ஜி பொண்ணு"). NEVER transliterate English words or acronyms into Tamil letters."""
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
You are an elite multilingual studio vocal transcription engine, Tamil acoustic phonetics specialist, and precision LRC time-alignment system.
Listen deeply to the entire vocal performance in this audio ($songContext) and transcribe every sung line into exact synchronized LRC format.

CRITICAL REQUIREMENTS:
1. VOCAL ISOLATION & COMPLETENESS:
   - Isolate and transcribe lead vocals, duet/chorus vocals, rapid sangathis/fast syllables, rap verses, humming hooks, and backing vocal call-and-response lines across the full track from 00:00.00 to the end${if (durationContext != null) " ($durationContext)" else ""}, even during dense Kuthu percussion, heavy bass, or loud orchestration.
   - Use the track metadata ($songContext) as helpful context for song theme, proper nouns, and canonical chorus phrasing, while ALWAYS trusting the actual vocals audible in the recording.
   - Transcribe every repeated chorus or pallavi line at each exact timestamp it is sung; never summarize with "Chorus x2" or skip repeated lines.
2. EXACT SONG VOCAL TIMING (ZERO-DRIFT SYNCHRONIZATION):
   - Every line MUST start with an exact LRC timestamp: [mm:ss.xx] Lyric line
   - Anchor EVERY `[mm:ss.xx]` timestamp strictly to the **exact acoustic onset of the singer's first sung syllable** for that line (within ±50ms precision)—NOT to the start of the preceding drum beat or instrumental measure, and NOT delayed past the first word.
   - Track true continuous elapsed time across the entire audio file (especially after long intros and BGM interludes) so timestamps NEVER drift early or late in the second half of the song.
   - Never guess or space timestamps at uniform fixed intervals (such as every 2.00s or 4.00s); follow the vocalist's real phrasing, breath pauses, pickups, and tempo changes.
   - Split lyrics into clean, singable single-phrase lines at natural breath/musical boundaries so real-time lyric highlighting locks onto the singer smoothly.
$languageInstruction
4. INSTRUMENTAL BREAKS SYMBOL:
   - For clearly audible instrumental intros, BGMs, interludes, or outros with no sung vocals (lasting ≥ 4 seconds), output a timestamp at the exact start of the instrumental passage followed ONLY by the musical note character: ♪ (for example: [00:00.00] ♪), then anchor the very next vocal line at the exact millisecond singing resumes.
   - NEVER use the 🎼, 🎵, or 🎶 emojis anywhere in the output. Never place ♪ over active singing.
5. NATURAL PUNCTUATION:
   - Use natural punctuation matching the sung phrasing and emotion (commas, periods, apostrophes, question marks, exclamation marks, or ellipses when appropriate) without altering the sung words.
6. OUTPUT FORMAT:
   - Return ONLY the raw LRC formatted lines. Do NOT include markdown wrappers (no ```lrc), headings, explanations, or notes.$correctionBlock
""".trimIndent()
    }

    internal fun cleanLrcTextResponse(raw: String): String {
        var clean = raw.trim()
        if (clean.startsWith("```")) {
            val lines = clean.lines()
            val filtered = lines.filterNot { it.trim().startsWith("```") }
            clean = filtered.joinToString("\n").trim()
        }
        // Replace any 🎼, 🎵, or 🎶 emojis with the clean musical note symbol ♪
        clean = clean
            .replace("🎼", "♪")
            .replace("🎵", "♪")
            .replace("🎶", "♪")
        return clean
    }

    /**
     * Cleans and validates transcribed LRC output:
     * - Strips accidental markdown/commentary noise
     * - Clamps out-of-range timestamps to the song's real duration when known
     * - Repairs any non-monotonic timestamp glitches so lyrics progress cleanly in real time
     * - Collapses consecutive redundant instrumental ♪ markers
     */
    internal fun normalizeTranscribedLrc(rawResponse: String, songDurationMs: Long): String {
        val cleaned = cleanLrcTextResponse(rawResponse)
        val lineTagRegex = Regex("""^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]\s*(.*)$""")
        val maxTimeMs = if (songDurationMs > 0L) songDurationMs else Long.MAX_VALUE
        val normalized = mutableListOf<Pair<Long, String>>()
        var lastTimeMs = -1L

        for (rawLine in cleaned.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val match = lineTagRegex.matchEntire(trimmed) ?: continue
            val minutes = match.groupValues[1].toLongOrNull() ?: continue
            val seconds = match.groupValues[2].toLongOrNull() ?: continue
            val fracRaw = match.groupValues[3]
            val millis = when (fracRaw.length) {
                0 -> 0L
                1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                else -> (fracRaw.take(3).toLongOrNull() ?: 0L)
            }
            val text = match.groupValues[4].trim()
            if (text.isEmpty()) continue

            var tMs = (minutes * 60_000L + seconds * 1_000L + millis).coerceAtLeast(0L)
            if (tMs > maxTimeMs) {
                // Skip hallucinated lyrics far past the end of the track (>5s past duration)
                if (tMs > maxTimeMs + 5_000L) continue
                tMs = maxTimeMs
            }
            if (lastTimeMs >= 0L && tMs < lastTimeMs) {
                tMs = lastTimeMs
            }
            // Collapse duplicate consecutive instrumental markers
            if (text == "♪" && normalized.lastOrNull()?.second == "♪") {
                continue
            }
            normalized.add(tMs to text)
            lastTimeMs = tMs
        }

        if (normalized.isEmpty()) return cleaned

        return normalized.joinToString("\n") { (timeMs, text) ->
            val minutes = timeMs / 60_000L
            val seconds = (timeMs % 60_000L) / 1_000L
            val centis = (timeMs % 1_000L) / 10L
            String.format(Locale.US, "[%02d:%02d.%02d] %s", minutes, seconds, centis, text)
        }
    }

    /**
     * If Gemini altered timestamps slightly during text translation, align translated text
     * back to the exact original timestamps 1:1.
     */
    private fun alignTimestampsWithOriginal(
        originalLrc: String,
        translatedLrc: String,
        translatedSyncedJson: String?
    ): String? {
        val originalLines = LrcParser.parseLrcToLines(originalLrc)
        val translatedLines = LrcParser.parseLrcToLines(translatedLrc)

        if (originalLines.isNotEmpty() && translatedLines.isNotEmpty() && originalLines.size == translatedLines.size) {
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

    private fun executeGeminiTextRequest(
        model: String,
        apiKey: String,
        prompt: String,
        isCorrectionPass: Boolean = false,
        responseTimeoutMs: Long = modelResponseTimeoutMs
    ): String {
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
                put("temperature", if (isCorrectionPass) 0.0 else 0.1)
                put("topP", 0.9)
            })
        }

        return executeStreamingOrStandardRequest(
            model = model,
            apiKey = apiKey,
            requestJson = requestJson,
            responseTimeoutMs = responseTimeoutMs,
            errorPrefix = "Gemini API error"
        )
    }

    private fun executeGeminiAudioRequest(
        model: String,
        apiKey: String,
        prompt: String,
        audioBase64: String,
        mimeType: String,
        isCorrectionPass: Boolean = false,
        responseTimeoutMs: Long = modelResponseTimeoutMs
    ): String {
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
                put("temperature", if (isCorrectionPass) 0.0 else 0.05)
                put("topP", 0.85)
            })
        }

        return executeStreamingOrStandardRequest(
            model = model,
            apiKey = apiKey,
            requestJson = requestJson,
            responseTimeoutMs = responseTimeoutMs,
            errorPrefix = "Gemini audio transcription error"
        )
    }

    /**
     * Uses Gemini's SSE streaming endpoint (`:streamGenerateContent?alt=sse`) with a strict
     * per-model response timeout (15s for higher-tier models). If a model stalls or fails to
     * emit its first text token within [responseTimeoutMs], the call is cancelled immediately
     * so the caller can switch to the next best audio-capable model without making the user wait.
     */
    private fun executeStreamingOrStandardRequest(
        model: String,
        apiKey: String,
        requestJson: JSONObject,
        responseTimeoutMs: Long,
        errorPrefix: String
    ): String {
        val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val url = "${normalizedBase}$model:streamGenerateContent?alt=sse"
        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Accept", "text/event-stream, application/json")
            .post(body)
            .build()

        val perAttemptClient = httpClient.newBuilder()
            .connectTimeout(minOf(10_000L, responseTimeoutMs), TimeUnit.MILLISECONDS)
            .writeTimeout(30_000L, TimeUnit.MILLISECONDS)
            .readTimeout(responseTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val call = perAttemptClient.newCall(request)
        val startedAtMs = System.currentTimeMillis()

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403 ||
                    (response.code == 400 && isInvalidApiKeyError(errBody))
                ) {
                    throw SecurityException(
                        "There was an error connecting with your Gemini API key. Please review and add the correct key in Settings → Audio & Sound."
                    )
                }
                throw IllegalStateException("$errorPrefix (HTTP ${response.code}): $errBody")
            }

            val responseBody = response.body ?: return ""
            val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
            if (contentType.contains("application/json") && !contentType.contains("text/event-stream")) {
                val respString = responseBody.string()
                return extractTextFromGeminiResponse(respString)
            }

            val accumulated = StringBuilder()
            var receivedAnyText = false
            val rawFallbackBuffer = StringBuilder()

            BufferedReader(responseBody.charStream()).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    if (trimmed.startsWith("data:")) {
                        val payload = trimmed.removePrefix("data:").trim()
                        if (payload == "[DONE]" || payload.isEmpty()) continue
                        val chunkText = extractTextFromGeminiResponse(payload)
                        if (chunkText.isNotEmpty()) {
                            receivedAnyText = true
                            accumulated.append(chunkText)
                        }
                    } else if (!receivedAnyText) {
                        rawFallbackBuffer.append(line).append('\n')
                    }

                    // Enforce strict first-token deadline so slow thinking/stalled models fail over at 15s
                    if (!receivedAnyText && (System.currentTimeMillis() - startedAtMs) > responseTimeoutMs) {
                        call.cancel()
                        throw SocketTimeoutException("Model $model did not start responding within ${responseTimeoutMs}ms")
                    }
                }
            }

            if (accumulated.isNotEmpty()) {
                return accumulated.toString()
            }
            val fallbackRaw = rawFallbackBuffer.toString().trim()
            return if (fallbackRaw.isNotEmpty()) extractTextFromGeminiResponse(fallbackRaw) else ""
        }
    }

    internal fun extractTextFromGeminiResponse(jsonString: String): String {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val chunkObj = arr.optJSONObject(i) ?: continue
                    sb.append(extractTextFromCandidateObject(chunkObj))
                }
                sb.toString()
            } else {
                extractTextFromCandidateObject(JSONObject(trimmed))
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractTextFromCandidateObject(json: JSONObject): String {
        val candidates = json.optJSONArray("candidates") ?: return ""
        val firstCandidate = candidates.optJSONObject(0) ?: return ""
        val content = firstCandidate.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            // Skip internal reasoning/thought parts so only actual transcribed lyrics are kept
            if (part.optBoolean("thought", false)) continue
            val text = part.optString("text", "")
            if (text.isNotEmpty()) {
                sb.append(text)
            }
        }
        return sb.toString()
    }

    private fun detectAudioMimeType(file: File): String {
        return when (file.extension.lowercase(Locale.US)) {
            "mp3" -> "audio/mp3"
            "m4a", "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/ogg"
            else -> "audio/mp3"
        }
    }

    private fun readAudioAsBase64(file: File): String {
        val fileSize = file.length()
        val readBytes = if (fileSize > MAX_AUDIO_INLINE_BYTES) {
            val buffer = ByteArray(MAX_AUDIO_INLINE_BYTES)
            FileInputStream(file).use { input ->
                var totalRead = 0
                while (totalRead < MAX_AUDIO_INLINE_BYTES) {
                    val n = input.read(buffer, totalRead, MAX_AUDIO_INLINE_BYTES - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }
                if (totalRead < MAX_AUDIO_INLINE_BYTES) buffer.copyOf(totalRead) else buffer
            }
        } else {
            file.readBytes()
        }
        return Base64.encodeToString(readBytes, Base64.NO_WRAP)
    }
}
