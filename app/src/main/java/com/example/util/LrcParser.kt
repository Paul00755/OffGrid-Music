package com.example.util

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object LrcParser {

    private val TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[\\.:](\\d{1,3}))?\\]")
    private val MEDIASTORE_DUP_SUFFIX_REGEX = Regex("^(.+?)(?:\\s*\\(\\d+\\))?\\.(lrc(?:\\.txt)?)(?:\\s*\\(\\d+\\))?$", RegexOption.IGNORE_CASE)
    private val pathLocks = ConcurrentHashMap<String, Any>()

    private fun lockForKey(key: String): Any = pathLocks.computeIfAbsent(key.lowercase()) { Any() }

    data class LrcLine(val timeMs: Long, val text: String)

    private fun isLrcCandidateFilename(name: String): Boolean {
        val lower = name.lowercase().trim()
        return lower.endsWith(".lrc") ||
            lower.endsWith(".lrc.txt") ||
            MEDIASTORE_DUP_SUFFIX_REGEX.matches(lower)
    }

    private fun extractLrcStemWithoutDupSuffix(fileName: String): String? {
        val trimmed = fileName.trim()
        val dupMatch = MEDIASTORE_DUP_SUFFIX_REGEX.matchEntire(trimmed)
        if (dupMatch != null) {
            return dupMatch.groupValues[1].trim()
        }
        val lower = trimmed.lowercase()
        return when {
            lower.endsWith(".lrc.txt") -> trimmed.dropLast(8).trim()
            lower.endsWith(".lrc") -> trimmed.dropLast(4).trim()
            else -> null
        }
    }

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

        // Strict normalized match in same directory (avoid matching unrelated songs in shared folders)
        val lrcFiles = parentDir.listFiles { _, name ->
            name.endsWith(".lrc", ignoreCase = true) && !name.endsWith(".lrc.txt", ignoreCase = true)
        }
        if (!lrcFiles.isNullOrEmpty()) {
            val normalizedBase = baseName.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            if (normalizedBase.length >= 4) {
                for (file in lrcFiles) {
                    val normFile = file.nameWithoutExtension.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
                    if (normFile.length >= 4 && (normFile == normalizedBase || normFile == "${normalizedBase}lyrics")) {
                        return file
                    }
                }
            }
        }

        return null
    }

    /**
     * Deletes all local `.lrc`, `.lrc.txt`, and MediaProvider numbered duplicate files (e.g. `.lrc (1)`,
     * `.lrc (2)`, `Song (1).lrc`) associated with [songPath] (and optional [songTitle])
     * as well as the cached `<songId>.lrc` file when [songId] is provided, except [keepFile] if specified.
     */
    fun deleteAllMatchingLocalLrcFiles(
        context: android.content.Context?,
        songId: Long = 0L,
        songPath: String,
        songTitle: String? = null,
        keepFile: File? = null
    ) {
        if (context != null && songId > 0L) {
            synchronized(lockForKey("cache_$songId")) {
                try {
                    val cacheFile = File(File(context.cacheDir, "lyrics"), "$songId.lrc")
                    if (cacheFile.exists() && (keepFile == null || cacheFile.absolutePath != keepFile.absolutePath)) {
                        cacheFile.delete()
                    }
                } catch (_: Exception) {}
            }
        }
        if (songPath.isBlank()) return
        val audioFile = File(songPath)
        val parentDir = audioFile.parentFile ?: return
        if (!parentDir.exists() || !parentDir.isDirectory) return

        val baseName = audioFile.nameWithoutExtension
        val canonicalKey = File(parentDir, "$baseName.lrc").absolutePath
        synchronized(lockForKey(canonicalKey)) {
            val candidateNames = mutableSetOf(
                "$baseName.lrc",
                "$baseName.LRC",
                "$baseName - lyrics.lrc",
                "$baseName [Lyrics].lrc",
                "${baseName.lowercase()}.lrc",
                "$baseName.lrc.txt",
                "$baseName.LRC.txt"
            )
            val safeTitle = songTitle?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim().orEmpty()
            if (safeTitle.isNotBlank()) {
                candidateNames.add("$safeTitle.lrc")
                candidateNames.add("$safeTitle.LRC")
                candidateNames.add("$safeTitle.lrc.txt")
            }

            val normalizedBase = baseName.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            val normalizedTitle = safeTitle.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            val matchingFiles = mutableListOf<File>()
            for (name in candidateNames) {
                val f = File(parentDir, name)
                if (f.exists() && f.isFile) matchingFiles.add(f)
            }
            try {
                parentDir.listFiles { _, name ->
                    isLrcCandidateFilename(name)
                }?.forEach { file ->
                    val stem = extractLrcStemWithoutDupSuffix(file.name) ?: return@forEach
                    val lowerStem = stem.lowercase()
                    val normStem = lowerStem.replace(Regex("[^\\p{L}\\p{N}]"), "")
                    val isExactStemMatch = lowerStem == baseName.lowercase() ||
                        (safeTitle.isNotBlank() && lowerStem == safeTitle.lowercase())
                    val isBaseNormMatch = normalizedBase.length >= 4 &&
                        (normStem == normalizedBase || normStem == "${normalizedBase}lyrics")
                    val isTitleNormMatch = normalizedTitle.length >= 4 &&
                        (normStem == normalizedTitle || normStem == "${normalizedTitle}lyrics")
                    if (isExactStemMatch || isBaseNormMatch || isTitleNormMatch) {
                        matchingFiles.add(file)
                    }
                }
            } catch (_: Exception) {}

            val keepPath = keepFile?.absolutePath
            for (file in matchingFiles.distinctBy { it.absolutePath }) {
                if (keepPath != null && file.absolutePath == keepPath) continue
                val absPath = file.absolutePath
                val fileName = file.name
                try { file.delete() } catch (_: Exception) {}
                if (context != null) {
                    removeMediaStoreEntryByPathOrName(context, absPath, fileName)
                }
            }

            if (context != null) {
                removeMediaStoreDuplicatesForBaseName(context, parentDir, baseName, keepFileName = keepFile?.name)
            }
        }
    }

    /**
     * Writes the cached `<songId>.lrc` file in `context.cacheDir/lyrics/` with the current [offsetMs].
     */
    fun writeCachedLrcFile(
        context: android.content.Context,
        songId: Long,
        plainLyrics: String?,
        syncedLyricsJson: String?,
        offsetMs: Long = 0L
    ): File? {
        if (songId <= 0L) return null
        return synchronized(lockForKey("cache_$songId")) {
            try {
                val cacheDir = File(context.cacheDir, "lyrics")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val cacheFile = File(cacheDir, "$songId.lrc")
                val content = formatLyricsToLrcContent(
                    plainLyrics = plainLyrics,
                    syncedLyricsJson = syncedLyricsJson,
                    rawLrcContent = null,
                    offsetMs = offsetMs
                )
                if (content.isNullOrBlank()) {
                    if (cacheFile.exists()) cacheFile.delete()
                    null
                } else {
                    val trimmedContent = content.trim()
                    if (cacheFile.exists()) {
                        val existing = try { cacheFile.readText(Charsets.UTF_8).trim() } catch (_: Exception) { "" }
                        if (existing == trimmedContent) {
                            return@synchronized cacheFile
                        }
                    }
                    cacheFile.writeText(content, Charsets.UTF_8)
                    cacheFile
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Converts any legacy `.lrc.txt` (or `.lrc.txt (N)`) files in [directory] into true `.lrc` files and deletes
     * the `.lrc.txt` duplicates so external music players and file managers see clean `.lrc` files.
     */
    fun cleanupLegacyLrcTxtFiles(context: android.content.Context?, directory: File?) {
        if (directory == null || !directory.exists() || !directory.isDirectory) return
        try {
            val badFiles = directory.listFiles { _, name ->
                val lower = name.lowercase()
                lower.endsWith(".lrc.txt") || Regex("^.+\\.lrc\\.txt\\s*\\(\\d+\\)$", RegexOption.IGNORE_CASE).matches(lower)
            } ?: return

            for (badFile in badFiles) {
                try {
                    val stem = extractLrcStemWithoutDupSuffix(badFile.name)
                    val properName = if (!stem.isNullOrBlank()) "$stem.lrc" else badFile.name.replace(Regex("\\.txt(?:\\s*\\(\\d+\\))?$", RegexOption.IGNORE_CASE), "")
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

    private fun computeRelativePath(parentDir: File): String {
        val rawParentPath = parentDir.absolutePath
        val relPath = when {
            rawParentPath.startsWith("/storage/emulated/0/") ->
                rawParentPath.removePrefix("/storage/emulated/0/").trim('/')
            rawParentPath.contains("/Music") ->
                "Music" + rawParentPath.substringAfter("/Music")
            else -> android.os.Environment.DIRECTORY_MUSIC
        }
        return if (relPath.endsWith("/")) relPath else "$relPath/"
    }

    private fun removeMediaStoreDuplicatesForBaseName(
        context: android.content.Context,
        parentDir: File,
        baseName: String,
        keepFileName: String?
    ) {
        try {
            val resolver = context.contentResolver
            val normalizedRelPath = computeRelativePath(parentDir)
            val urisToClean = mutableListOf(android.provider.MediaStore.Files.getContentUri("external"))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                urisToClean.add(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            for (collectionUri in urisToClean) {
                try {
                    val selection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                    } else {
                        "${android.provider.MediaStore.MediaColumns.DATA} LIKE ?"
                    }
                    val args = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        arrayOf(normalizedRelPath, "$baseName%.lrc%")
                    } else {
                        arrayOf("${parentDir.absolutePath}/$baseName%.lrc%")
                    }
                    val dupIds = mutableListOf<Long>()
                    resolver.query(
                        collectionUri,
                        arrayOf(android.provider.BaseColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                        selection,
                        args,
                        null
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndex(android.provider.BaseColumns._ID)
                        val nameCol = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val id = if (idCol >= 0) cursor.getLong(idCol) else -1L
                            val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                            if (id <= 0L || displayName.isBlank()) continue
                            if (keepFileName != null && displayName.equals(keepFileName, ignoreCase = true)) continue
                            val stem = extractLrcStemWithoutDupSuffix(displayName)
                            if (stem != null && stem.equals(baseName, ignoreCase = true)) {
                                dupIds.add(id)
                            }
                        }
                    }
                    for (dupId in dupIds) {
                        try {
                            resolver.delete(android.content.ContentUris.withAppendedId(collectionUri, dupId), null, null)
                        } catch (_: Exception) {}
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

    fun formatLyricsToLrcContent(
        plainLyrics: String? = null,
        syncedLyricsJson: String? = null,
        rawLrcContent: String? = null,
        offsetMs: Long = 0L
    ): String? {
        val body = when {
            !syncedLyricsJson.isNullOrBlank() -> formatSyncedJsonToLrc(syncedLyricsJson, offsetMs = 0L)
            !rawLrcContent.isNullOrBlank() && parseLrcToLines(rawLrcContent).isNotEmpty() -> {
                val (_, json) = parseLrcContent(rawLrcContent)
                formatSyncedJsonToLrc(json, offsetMs = 0L) ?: stripOffsetLines(rawLrcContent)
            }
            !plainLyrics.isNullOrBlank() -> stripOffsetLines(plainLyrics)
            !rawLrcContent.isNullOrBlank() -> stripOffsetLines(rawLrcContent)
            else -> null
        }?.trim()

        if (body.isNullOrBlank()) return null
        return if (offsetMs != 0L) {
            "[offset:$offsetMs]\n$body"
        } else {
            body
        }
    }

    private fun stripOffsetLines(content: String): String {
        return content.lines()
            .filterNot { it.trim().startsWith("[offset:", ignoreCase = true) }
            .joinToString("\n")
            .trim()
    }

    /**
     * Writes synced or plain lyrics as a standard `.lrc` file directly in the exact folder where the
     * audio file is stored (`<audioFile.nameWithoutExtension>.lrc`), including `[offset:...]` if set,
     * and deletes any old/conflicting `.lrc` or duplicate `(1)`/`(2)` files for this song.
     */
    fun writeCompanionLrcForSong(
        context: android.content.Context?,
        songPath: String,
        songTitle: String? = null,
        plainLyrics: String? = null,
        syncedLyricsJson: String? = null,
        rawLrcContent: String? = null,
        offsetMs: Long = 0L,
        skipDirectoryCleanup: Boolean = false
    ): File? {
        if (songPath.isBlank()) return null
        val audioFile = File(songPath)
        val parentDir = audioFile.parentFile ?: return null
        if (!parentDir.exists()) return null

        val baseName = audioFile.nameWithoutExtension
        val lrcFileName = "$baseName.lrc"
        val targetLrcFile = File(parentDir, lrcFileName)

        return synchronized(lockForKey(targetLrcFile.absolutePath)) {
            val formattedLrc = formatLyricsToLrcContent(
                plainLyrics = plainLyrics,
                syncedLyricsJson = syncedLyricsJson,
                rawLrcContent = rawLrcContent,
                offsetMs = offsetMs
            )
            if (formattedLrc.isNullOrBlank()) {
                deleteAllMatchingLocalLrcFiles(context, songId = 0L, songPath = songPath, songTitle = songTitle)
                return@synchronized null
            }

            val trimmedFormatted = formattedLrc.trim()

            // Fast-path during batch sync: if directory cleanup already ran for this folder and
            // the exact .lrc content is already written on disk, skip redundant scanning & I/O.
            if (skipDirectoryCleanup && targetLrcFile.exists()) {
                val existingContent = try { targetLrcFile.readText(Charsets.UTF_8).trim() } catch (_: Exception) { "" }
                if (existingContent == trimmedFormatted) {
                    return@synchronized targetLrcFile
                }
            }

            if (!skipDirectoryCleanup) {
                // Clean up any legacy .lrc.txt files in the song's folder and in Music/Lyrics
                cleanupLegacyLrcTxtFiles(context, parentDir)
                try {
                    val publicMusic = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                    cleanupLegacyLrcTxtFiles(context, publicMusic)
                    cleanupLegacyLrcTxtFiles(context, File(publicMusic, "Lyrics"))
                } catch (_: Exception) {}

                // Delete any other conflicting/fuzzy .lrc, .lrc.txt, or numbered duplicate (.lrc (1), (2)) files for this song
                deleteAllMatchingLocalLrcFiles(
                    context = context,
                    songId = 0L,
                    songPath = songPath,
                    songTitle = songTitle,
                    keepFile = targetLrcFile
                )
            }

            val badTxtFile = File(parentDir, "$lrcFileName.txt")

            // 1. Direct write to the song's folder (preferred, preserves exact .lrc extension and updates in-place)
            var written = false
            try {
                if (targetLrcFile.exists()) {
                    val existingContent = try { targetLrcFile.readText(Charsets.UTF_8).trim() } catch (_: Exception) { "" }
                    if (existingContent == trimmedFormatted) {
                        return@synchronized targetLrcFile
                    }
                    // Try truncating/overwriting in-place first before delete+recreate
                    try {
                        java.io.FileOutputStream(targetLrcFile, false).use { fos ->
                            fos.write(formattedLrc.toByteArray(Charsets.UTF_8))
                            fos.flush()
                        }
                        written = targetLrcFile.exists() && targetLrcFile.readText(Charsets.UTF_8).trim() == trimmedFormatted
                    } catch (_: Exception) {
                        written = false
                    }
                    if (!written) {
                        try { targetLrcFile.delete() } catch (_: Exception) {}
                    }
                }
                if (!written) {
                    targetLrcFile.writeText(formattedLrc, Charsets.UTF_8)
                    written = targetLrcFile.exists() && targetLrcFile.readText(Charsets.UTF_8).trim() == trimmedFormatted
                }
            } catch (_: Exception) {
                written = false
            }

            // 2. Scoped storage fallback into the song's exact relative directory using application/octet-stream
            // (NEVER use "text/plain", because Android MediaProvider automatically appends ".txt" for text/plain!)
            if (!written && context != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    val resolver = context.contentResolver
                    val externalUri = android.provider.MediaStore.Files.getContentUri("external")
                    val normalizedRelPath = computeRelativePath(parentDir)
                    val bytes = formattedLrc.toByteArray(Charsets.UTF_8)

                    // First query existing MediaStore URI for this exact .lrc file and overwrite in-place so MediaProvider never creates "(1)"
                    var existingContentUri: android.net.Uri? = null
                    try {
                        resolver.query(
                            externalUri,
                            arrayOf(android.provider.BaseColumns._ID),
                            "(${android.provider.MediaStore.MediaColumns.DATA} = ?) OR (${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?)",
                            arrayOf(targetLrcFile.absolutePath, lrcFileName, normalizedRelPath),
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idCol = cursor.getColumnIndex(android.provider.BaseColumns._ID)
                                if (idCol >= 0) {
                                    existingContentUri = android.content.ContentUris.withAppendedId(externalUri, cursor.getLong(idCol))
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (existingContentUri != null) {
                        written = writeBytesToContentUri(resolver, existingContentUri!!, bytes)
                    }

                    if (!written) {
                        // Clean up any conflicting/stale MediaStore entries in this directory before inserting
                        try {
                            if (existingContentUri != null) {
                                resolver.delete(existingContentUri!!, null, null)
                            }
                            resolver.delete(
                                externalUri,
                                "(${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${android.provider.MediaStore.MediaColumns.DATA} = ?) AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                                arrayOf(lrcFileName, "$lrcFileName.txt", targetLrcFile.absolutePath, normalizedRelPath)
                            )
                        } catch (_: Exception) {}
                        removeMediaStoreDuplicatesForBaseName(context, parentDir, baseName, keepFileName = null)

                        // Only insert via MediaStore when the target file does not still block the filename on disk;
                        // otherwise MediaProvider will auto-rename the inserted row to "Song.lrc (1)".
                        if (!targetLrcFile.exists() || targetLrcFile.delete()) {
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, lrcFileName)
                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, normalizedRelPath)
                            }
                            val uri = resolver.insert(externalUri, values)
                            if (uri != null) {
                                val actualName = try {
                                    resolver.query(
                                        uri,
                                        arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                                        null,
                                        null,
                                        null
                                    )?.use { c ->
                                        if (c.moveToFirst()) c.getString(0) else null
                                    }
                                } catch (_: Exception) { null }

                                if (actualName != null && !actualName.equals(lrcFileName, ignoreCase = true)) {
                                    // MediaProvider created a renamed duplicate such as "Song.lrc (1)" -> remove it immediately
                                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                                } else {
                                    written = writeBytesToContentUri(resolver, uri, bytes)
                                }
                            }
                        }
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

            if (targetLrcFile.exists()) targetLrcFile else null
        }
    }

    private fun writeBytesToContentUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        bytes: ByteArray
    ): Boolean {
        try {
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
                return true
            }
        } catch (_: Exception) {}
        try {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.channel.truncate(0L)
                    fos.write(bytes)
                    fos.flush()
                    fos.fd.sync()
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun formatSyncedJsonToLrc(syncedJson: String?, offsetMs: Long = 0L): String? {
        if (syncedJson.isNullOrBlank()) return null
        return try {
            val array = org.json.JSONArray(syncedJson)
            val sb = StringBuilder()
            if (offsetMs != 0L) {
                sb.append("[offset:$offsetMs]\n")
            }
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

    fun extractOffsetFromLrc(content: String?): Long? {
        if (content.isNullOrBlank()) return null
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[offset:", ignoreCase = true)) {
                val matcher = OFFSET_TAG_PATTERN.matcher(trimmed)
                if (matcher.find()) {
                    return matcher.group(1)?.toLongOrNull()
                }
            }
        }
        return null
    }

    fun parseLrcContent(content: String): Pair<String?, String?> {
        if (content.isBlank()) return Pair(null, null)

        val cleanContent = if (content.trim().startsWith("[{")) {
            formatSyncedJsonToLrc(content) ?: content
        } else content

        val parsedLines = parseLrcToLines(cleanContent, applyEmbeddedOffset = false)

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

    fun parseLrcToLines(content: String, applyEmbeddedOffset: Boolean = false): List<LrcLine> {
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
                    val adjustedTimeMs = if (applyEmbeddedOffset) (timeMs - embeddedOffsetMs).coerceAtLeast(0L) else timeMs
                    parsedLines.add(LrcLine(adjustedTimeMs, finalText))
                }
            }
        }

        return parsedLines.sortedBy { it.timeMs }
    }
}
