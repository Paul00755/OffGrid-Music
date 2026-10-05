package com.example.service.transcription

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object LyricsTranslationCache {

    private const val CACHE_DIR_NAME = "lyrics_ai_cache"
    private const val MAX_ENTRIES = 500
    private const val MAX_TOTAL_BYTES = 15L * 1024L * 1024L // 15 MB

    private fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun computeSourceLyricsHash(plainLyrics: String?, syncedJson: String?): String {
        if (plainLyrics.isNullOrBlank() && syncedJson.isNullOrBlank()) return "empty"
        val raw = "${plainLyrics?.trim() ?: ""}:::${syncedJson?.trim() ?: ""}"
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) {
            raw.hashCode().toString()
        }
    }

    private fun buildCacheFileName(
        songId: Long,
        mode: AudioTranscribeMode,
        targetLang: TranscribeLanguage,
        sourceLyricsHash: String?
    ): String {
        val hashPart = if (mode == AudioTranscribeMode.TRANSLATE && !sourceLyricsHash.isNullOrBlank()) {
            "_$sourceLyricsHash"
        } else ""
        return "${songId}_${mode.id}_${targetLang.code}${hashPart}.json"
    }

    @Synchronized
    fun get(
        context: Context,
        songId: Long,
        mode: AudioTranscribeMode,
        targetLang: TranscribeLanguage,
        sourceLyricsHash: String?
    ): TranscribeResult.Success? {
        try {
            val dir = getCacheDir(context)
            val fileName = buildCacheFileName(songId, mode, targetLang, sourceLyricsHash)
            val file = File(dir, fileName)
            if (!file.exists() || !file.isFile) return null

            val jsonStr = file.readText(Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            val plain = json.optString("plainLyrics", "")
            val synced = json.optString("syncedLyricsJson", "").takeIf { it.isNotBlank() }

            if (plain.isBlank() && synced.isNullOrBlank()) return null

            // Touch file to keep LRU order
            file.setLastModified(System.currentTimeMillis())

            return TranscribeResult.Success(
                plainLyrics = plain,
                syncedLyricsJson = synced,
                isFromCache = true,
                language = targetLang,
                mode = mode
            )
        } catch (_: Exception) {
            return null
        }
    }

    @Synchronized
    fun put(
        context: Context,
        songId: Long,
        mode: AudioTranscribeMode,
        targetLang: TranscribeLanguage,
        sourceLyricsHash: String?,
        plainLyrics: String,
        syncedLyricsJson: String?
    ) {
        try {
            val dir = getCacheDir(context)
            pruneCacheIfNecessary(dir)

            val fileName = buildCacheFileName(songId, mode, targetLang, sourceLyricsHash)
            val file = File(dir, fileName)

            val json = JSONObject().apply {
                put("songId", songId)
                put("mode", mode.id)
                put("lang", targetLang.code)
                put("sourceHash", sourceLyricsHash ?: "")
                put("plainLyrics", plainLyrics)
                put("syncedLyricsJson", syncedLyricsJson ?: "")
                put("savedAt", System.currentTimeMillis())
            }

            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun pruneCacheIfNecessary(dir: File) {
        val files = dir.listFiles { f -> f.extension.equals("json", ignoreCase = true) } ?: return
        var totalBytes = files.sumOf { it.length() }

        if (files.size > MAX_ENTRIES || totalBytes > MAX_TOTAL_BYTES) {
            // Sort by oldest last modified
            val sorted = files.sortedBy { it.lastModified() }
            val removeCount = (sorted.size * 0.25).toInt().coerceAtLeast(1)
            for (i in 0 until removeCount) {
                sorted.getOrNull(i)?.delete()
            }
        }
    }

    @Synchronized
    fun clearCache(context: Context) {
        try {
            val dir = getCacheDir(context)
            dir.deleteRecursively()
        } catch (_: Exception) {}
    }
}
