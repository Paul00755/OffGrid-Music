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

        // Proactively convert any legacy .lrc.txt files in this folder to proper .lrc files
        cleanupLegacyLrcTxtFiles(null, parentDir)

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
        val lrcFiles = parentDir.listFiles { _, name ->
            name.endsWith(".lrc", ignoreCase = true) && !name.endsWith(".lrc.txt", ignoreCase = true)
        }
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

    /**
     * Converts any legacy `.lrc.txt` files in [directory] into true `.lrc` files and deletes
     * the `.lrc.txt` duplicates so external music players and file managers see clean `.lrc` files.
     */
    fun cleanupLegacyLrcTxtFiles(context: android.content.Context?, directory: File?) {
        if (directory == null || !directory.exists() || !directory.isDirectory) return
        try {
            val badFiles = directory.listFiles { _, name ->
                name.lowercase().endsWith(".lrc.txt")
            } ?: return

            for (badFile in badFiles) {
                try {
                    val properName = badFile.name.substring(0, badFile.name.length - 4) // strip ".txt"
                    val properFile = File(directory, properName)
                    val badPath = badFile.absolutePath

                    if (!properFile.exists() || badFile.lastModified() > properFile.lastModified()) {
                        val content = badFile.readText(Charsets.UTF_8)
                        if (content.isNotBlank()) {
                            properFile.writeText(content, Charsets.UTF_8)
                        }
                    }
                    badFile.delete()

                    if (context != null) {
                        removeMediaStoreEntryByPathOrName(context, badPath, badFile.name)
                        if (properFile.exists()) {
                            android.media.MediaScannerConnection.scanFile(
                                context,
                                arrayOf(properFile.absolutePath),
                                null,
                                null
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun removeMediaStoreEntryByPathOrName(
        context: android.content.Context,
        absolutePath: String,
        displayName: String
    ) {
        try {
            val resolver = context.contentResolver
            val filesUri = android.provider.MediaStore.Files.getContentUri("external")
            resolver.delete(
                filesUri,
                "${android.provider.MediaStore.MediaColumns.DATA} = ? OR ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(absolutePath, displayName)
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                resolver.delete(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(displayName)
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Writes synced lyrics as a standard `.lrc` file directly in the exact folder where the
     * audio file is stored (`<audioFile.nameWithoutExtension>.lrc`), never `.lrc.txt`.
     */
    fun writeCompanionLrcForSong(
        context: android.content.Context?,
        songPath: String,
        songTitle: String? = null,
        plainLyrics: String? = null,
        syncedLyricsJson: String? = null,
        rawLrcContent: String? = null
    ): File? {
        if (songPath.isBlank()) return null
        val audioFile = File(songPath)
        val parentDir = audioFile.parentFile ?: return null
        if (!parentDir.exists()) return null

        val formattedLrc = when {
            !syncedLyricsJson.isNullOrBlank() -> formatSyncedJsonToLrc(syncedLyricsJson)
            !rawLrcContent.isNullOrBlank() && parseLrcToLines(rawLrcContent).isNotEmpty() -> {
                val (_, json) = parseLrcContent(rawLrcContent)
                formatSyncedJsonToLrc(json) ?: rawLrcContent.trim()
            }
            else -> null
        }
        if (formattedLrc.isNullOrBlank()) return null

        // Clean up any legacy .lrc.txt files in the song's folder and in Music/Lyrics
        cleanupLegacyLrcTxtFiles(context, parentDir)
        try {
            val publicMusic = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
            cleanupLegacyLrcTxtFiles(context, publicMusic)
            cleanupLegacyLrcTxtFiles(context, File(publicMusic, "Lyrics"))
        } catch (_: Exception) {}

        val baseName = audioFile.nameWithoutExtension
        val lrcFileName = "$baseName.lrc"
        val targetLrcFile = File(parentDir, lrcFileName)

        // Also remove any lingering "<title>.lrc.txt" or "<baseName>.lrc.txt"
        val badTxtFile = File(parentDir, "$lrcFileName.txt")
        if (badTxtFile.exists()) {
            val badPath = badTxtFile.absolutePath
            badTxtFile.delete()
            if (context != null) {
                removeMediaStoreEntryByPathOrName(context, badPath, badTxtFile.name)
            }
        }
        if (!songTitle.isNullOrBlank()) {
            val safeTitle = songTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            if (safeTitle.isNotBlank()) {
                val badTitleTxt = File(parentDir, "$safeTitle.lrc.txt")
                if (badTitleTxt.exists()) {
                    val badPath = badTitleTxt.absolutePath
                    badTitleTxt.delete()
                    if (context != null) {
                        removeMediaStoreEntryByPathOrName(context, badPath, badTitleTxt.name)
                    }
                }
            }
        }

        // 1. Direct write to the song's folder (preferred, preserves exact .lrc extension)
        var written = false
        try {
            if (targetLrcFile.exists()) {
                val existingContent = try { targetLrcFile.readText(Charsets.UTF_8).trim() } catch (_: Exception) { "" }
                if (existingContent == formattedLrc.trim()) {
                    return targetLrcFile
                }
            }
            targetLrcFile.writeText(formattedLrc, Charsets.UTF_8)
            written = targetLrcFile.exists() && targetLrcFile.length() > 0L
        } catch (_: Exception) {
            written = false
        }

        // 2. Scoped storage fallback into the song's exact relative directory using application/octet-stream
        // (NEVER use "text/plain", because Android MediaProvider automatically appends ".txt" for text/plain!)
        if (!written && context != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val externalUri = android.provider.MediaStore.Files.getContentUri("external")
                val rawParentPath = parentDir.absolutePath
                val relPath = when {
                    rawParentPath.startsWith("/storage/emulated/0/") ->
                        rawParentPath.removePrefix("/storage/emulated/0/").trim('/')
                    rawParentPath.contains("/Music") ->
                        "Music" + rawParentPath.substringAfter("/Music")
                    else -> android.os.Environment.DIRECTORY_MUSIC
                }
                val normalizedRelPath = if (relPath.endsWith("/")) relPath else "$relPath/"

                resolver.delete(
                    externalUri,
                    "(${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?) AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(lrcFileName, "$lrcFileName.txt", normalizedRelPath)
                )

                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, lrcFileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, normalizedRelPath)
                }
                val uri = resolver.insert(externalUri, values)
                if (uri != null) {
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(formattedLrc.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    written = true
                }
            } catch (_: Exception) {}
        }

        // If MediaProvider still created a .lrc.txt file on disk, rename it back to .lrc immediately
        if (badTxtFile.exists()) {
            try {
                if (!targetLrcFile.exists()) {
                    badTxtFile.renameTo(targetLrcFile)
                } else {
                    badTxtFile.delete()
                }
            } catch (_: Exception) {}
        }

        if (written && context != null && targetLrcFile.exists()) {
            try {
                // Pass null for mimeTypes so MediaScanner never forces text/plain (.txt) onto .lrc files
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetLrcFile.absolutePath),
                    null,
                    null
                )
            } catch (_: Exception) {}
        }

        return if (targetLrcFile.exists()) targetLrcFile else null
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
                .filter { it.isNotEmpty() }
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

    private val OFFSET_TAG_PATTERN = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*\\]$", Pattern.CASE_INSENSITIVE)

    fun parseLrcToLines(content: String): List<LrcLine> {
        if (content.isBlank()) return emptyList()

        val lines = content.lines()
        val parsedLines = mutableListOf<LrcLine>()
        var embeddedOffsetMs = 0L

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("[offset:", ignoreCase = true)) {
                val offsetMatcher = OFFSET_TAG_PATTERN.matcher(trimmed)
                if (offsetMatcher.find()) {
                    embeddedOffsetMs = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
                }
                continue
            }

            // Skip metadata tags
            if (trimmed.startsWith("[ti:", ignoreCase = true) ||
                trimmed.startsWith("[ar:", ignoreCase = true) ||
                trimmed.startsWith("[al:", ignoreCase = true) ||
                trimmed.startsWith("[by:", ignoreCase = true) ||
                trimmed.startsWith("[length:", ignoreCase = true) ||
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
                val cleanText = trimmed
                    .replace(Regex("\\[\\d{1,3}:\\d{2}(?:[\\.:]\\d{1,3})?\\]"), "")
                    .replace("🎼", "♪")
                    .replace("🎵", "♪")
                    .replace("🎶", "♪")
                    .trim()
                val finalText = if (cleanText.isEmpty() || cleanText.all { it == '♪' || it.isWhitespace() }) "♪" else cleanText
                for (timeMs in lineTimes) {
                    val adjustedTimeMs = (timeMs - embeddedOffsetMs).coerceAtLeast(0L)
                    parsedLines.add(LrcLine(adjustedTimeMs, finalText))
                }
            }
        }

        return parsedLines.sortedBy { it.timeMs }
    }
}
