package com.example.scanner

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.service.transcription.SecureApiKeyStore
import com.example.util.TagCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional verification service using Gemini (gemini-3.5-flash with googleSearch tool grounding)
 * to verify and correct spelling of highly ambiguous or noisy song titles.
 *
 * Rules:
 * - Local cleaned filename metadata is always the primary indexing dependency.
 * - Used as an optional fallback when excessive source tags make the local title ambiguous.
 * - Never blocks local scanning (strictly bounded by short coroutine timeout).
 * - Fails silently back to local title on network/API errors.
 * - Never overwrites user-edited metadata.
 */
object SongVerificationService {
    private const val TAG = "SongVerification"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val verificationCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun verifySongTitleIfAmbiguous(
        context: Context,
        rawTitle: String?,
        rawFilename: String
    ): String = withContext(Dispatchers.IO) {
        val localCleaned = TagCleaner.cleanTitle(rawTitle, rawFilename)

        // Only query external verification if raw input has excessive noise indicators
        if (!isExcessivelyNoisy(rawTitle, rawFilename)) {
            return@withContext localCleaned
        }

        val cacheKey = "${rawTitle ?: ""}|$rawFilename"
        verificationCache[cacheKey]?.let { return@withContext it }

        val apiKey = try {
            val keyStore = SecureApiKeyStore(context)
            keyStore.getApiKey()?.takeIf { it.isNotBlank() }
                ?: BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() && !it.contains("MY_") }
        } catch (_: Throwable) {
            null
        }

        if (apiKey.isNullOrBlank()) {
            return@withContext localCleaned
        }

        val verified = withTimeoutOrNull(2500L) {
            queryGeminiSearchGrounding(apiKey, rawTitle, rawFilename, localCleaned)
        }

        val result = if (!verified.isNullOrBlank() && !verified.equals("NONE", ignoreCase = true) && verified.length in 2..100) {
            TagCleaner.cleanTitle(verified, rawFilename)
        } else {
            localCleaned
        }

        verificationCache[cacheKey] = result
        result
    }

    private fun isExcessivelyNoisy(rawTitle: String?, rawFilename: String): Boolean {
        val combined = "${rawTitle ?: ""} $rawFilename".lowercase()
        var signals = 0
        if (combined.contains("masstamilan")) signals++
        if (combined.contains("pagalworld") || combined.contains("pagal world")) signals++
        if (combined.contains("isaimini") || combined.contains("starmusiq")) signals++
        if (combined.contains("video song") || combined.contains("movie song")) signals++
        if (combined.contains("320kbps") || combined.contains("128kbps")) signals++
        if (combined.contains("cdrip") || combined.contains("dvdrip")) signals++
        return signals >= 2
    }

    private fun queryGeminiSearchGrounding(
        apiKey: String,
        rawTitle: String?,
        rawFilename: String,
        localCleaned: String
    ): String? {
        return try {
            val prompt = "Identify the official clean song title for audio file with filename '$rawFilename' and metadata '${rawTitle ?: ""}'. Current best local clean guess is '$localCleaned'. Return ONLY the exact official song title (no quotes, no release tags), or 'NONE' if uncertain."

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

                // Search Grounding tool (gemini-3.5-flash)
                val tools = JSONArray().apply {
                    put(JSONObject().apply {
                        put("googleSearch", JSONObject())
                    })
                }
                put("tools", tools)

                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                })
            }

            val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(BASE_URL)
                .header("x-goog-api-key", apiKey)
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val respString = response.body?.string() ?: return null
                val root = JSONObject(respString)
                val candidates = root.optJSONArray("candidates") ?: return null
                val firstCandidate = candidates.optJSONObject(0) ?: return null
                val content = firstCandidate.optJSONObject("content") ?: return null
                val parts = content.optJSONArray("parts") ?: return null
                val textPart = parts.optJSONObject(0) ?: return null
                val text = textPart.optString("text", "").trim()
                if (text.isBlank() || text.equals("NONE", ignoreCase = true)) null else text
            }
        } catch (_: Throwable) {
            null
        }
    }
}
