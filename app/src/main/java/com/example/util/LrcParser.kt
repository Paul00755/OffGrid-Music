package com.example.util

import java.io.File
import java.util.regex.Pattern

object LrcParser {

    private val TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[\\.:](\\d{1,3}))?\\]")

    data class LrcLine(val timeMs: Long, val text: String)

    fun findLocalLrcFile(audioPath: String): File? {
        if (audioPath.isBlank()) return null
        val audioFile = File(audioPath)
        if (!audioFile.exists()) return null

        val parentDir = audioFile.parentFile ?: return null
        val baseName = audioFile.nameWithoutExtension

        // Candidate names
        val candidates = listOf(
            "$baseName.lrc",
            "$baseName.LRC",
            "$baseName - lyrics.lrc",
            "$baseName [Lyrics].lrc",
            "${baseName.lowercase()}.lrc"
        )

        for (candidate in candidates) {
            val file = File(parentDir, candidate)
            if (file.exists() && file.isFile) {
                return file
            }
        }

        // Fuzzy match in same directory
        val lrcFiles = parentDir.listFiles { _, name -> name.endsWith(".lrc", ignoreCase = true) }
        if (!lrcFiles.isNullOrEmpty()) {
            val normalizedBase = baseName.lowercase().replace(Regex("[^a-z0-9]"), "")
            for (file in lrcFiles) {
                val normFile = file.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]"), "")
                if (normFile.contains(normalizedBase) || normalizedBase.contains(normFile)) {
                    return file
                }
            }
        }

        return null
    }

    fun formatSyncedJsonToLrc(syncedJson: String?): String? {
        if (syncedJson.isNullOrBlank()) return null
        return try {
            val array = org.json.JSONArray(syncedJson)
            val sb = StringBuilder()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val timeMs = obj.optLong("timeMs", 0L)
                val text = obj.optString("text", "")
                val min = timeMs / 60000
                val sec = (timeMs % 60000) / 1000
                val ms = (timeMs % 1000) / 10
                sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d] %s\n", min, sec, ms, text))
            }
            sb.toString().trim()
        } catch (e: Exception) {
            syncedJson
        }
    }

    fun parseLrcContent(content: String): Pair<String?, String?> {
        if (content.isBlank()) return Pair(null, null)

        val cleanContent = if (content.trim().startsWith("[{")) {
            formatSyncedJsonToLrc(content) ?: content
        } else content

        val parsedLines = parseLrcToLines(cleanContent)

        val plainLyrics: String? = if (parsedLines.isNotEmpty()) {
            val plainTextLines = parsedLines
                .map { it.text.trim() }
                .filter { it.isNotEmpty() && it != "♪" }
            if (plainTextLines.isNotEmpty()) plainTextLines.joinToString("\n") else null
        } else {
            // Unsynced/plain text LRC file fallback
            val rawLines = cleanContent.lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() &&
                    !line.startsWith("[ti:", ignoreCase = true) &&
                    !line.startsWith("[ar:", ignoreCase = true) &&
                    !line.startsWith("[al:", ignoreCase = true) &&
                    !line.startsWith("[by:", ignoreCase = true) &&
                    !line.startsWith("[offset:", ignoreCase = true)
                }
            if (rawLines.isNotEmpty()) rawLines.joinToString("\n") else cleanContent.trim()
        }

        val syncedJson = if (parsedLines.isNotEmpty()) {
            val jsonArray = org.json.JSONArray()
            for (item in parsedLines) {
                val obj = org.json.JSONObject()
                obj.put("timeMs", item.timeMs)
                obj.put("text", item.text)
                jsonArray.put(obj)
            }
            jsonArray.toString()
        } else null

        return Pair(plainLyrics, syncedJson)
    }

    fun parseLrcToLines(content: String): List<LrcLine> {
        if (content.isBlank()) return emptyList()

        val lines = content.lines()
        val parsedLines = mutableListOf<LrcLine>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Skip metadata tags
            if (trimmed.startsWith("[ti:", ignoreCase = true) ||
                trimmed.startsWith("[ar:", ignoreCase = true) ||
                trimmed.startsWith("[al:", ignoreCase = true) ||
                trimmed.startsWith("[by:", ignoreCase = true) ||
                trimmed.startsWith("[length:", ignoreCase = true) ||
                trimmed.startsWith("[offset:", ignoreCase = true) ||
                trimmed.startsWith("[re:", ignoreCase = true) ||
                trimmed.startsWith("[ve:", ignoreCase = true)
            ) {
                continue
            }

            val matcher = TIMESTAMP_PATTERN.matcher(trimmed)
            val lineTimes = mutableListOf<Long>()

            while (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val fracStr = matcher.group(3) ?: "0"
                val frac = when (fracStr.length) {
                    1 -> fracStr.toLongOrNull()?.times(100) ?: 0L
                    2 -> fracStr.toLongOrNull()?.times(10) ?: 0L
                    else -> fracStr.toLongOrNull() ?: 0L
                }

                val timeMs = (min * 60 * 1000) + (sec * 1000) + frac
                lineTimes.add(timeMs)
            }

            if (lineTimes.isNotEmpty()) {
                val cleanText = trimmed.replace(Regex("\\[\\d{1,3}:\\d{2}(?:[\\.:]\\d{1,3})?\\]"), "").trim()
                val finalText = if (cleanText.isEmpty()) "♪" else cleanText
                for (timeMs in lineTimes) {
                    parsedLines.add(LrcLine(timeMs, finalText))
                }
            }
        }

        return parsedLines.sortedBy { it.timeMs }
    }
}
