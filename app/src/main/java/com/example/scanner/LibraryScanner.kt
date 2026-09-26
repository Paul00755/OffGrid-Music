package com.example.scanner

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.data.local.FolderEntity
import com.example.data.local.SongDao
import com.example.data.local.SongEntity
import com.example.data.SampleDataProvider
import com.example.util.ArtworkManager
import com.example.util.TagCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class ScanProgress(
    val isScanning: Boolean = false,
    val totalDiscovered: Int = 0,
    val processedCount: Int = 0,
    val currentFolder: String = "",
    val isFinished: Boolean = false,
    val error: String? = null
)

class LibraryScanner(
    private val context: Context,
    private val songDao: SongDao
) {
    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: Flow<ScanProgress> = _scanProgress.asStateFlow()

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "amr", "3gp", "m4r", "aiff"
    )

    suspend fun scanLibrary(
        includedFolders: List<FolderEntity> = emptyList(),
        excludedFolders: List<FolderEntity> = emptyList(),
        ignoreAudioDurationMs: Long = 0L
    ) = withContext(Dispatchers.IO) {
        _scanProgress.value = ScanProgress(
            isScanning = true,
            totalDiscovered = 0,
            processedCount = 0,
            currentFolder = "Searching media index...",
            isFinished = false
        )

        val normExcludedPaths = excludedFolders.map { normalizePath(it.path) }
        val normIncludedPaths = includedFolders.map { normalizePath(it.path) }

        val foundSongsMap = mutableMapOf<String, SongEntity>()
        var lastEmitTime = 0L

        // 1. Resilient MediaStore Scan for indexed audio files across the device
        try {
            val projectionList = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATA
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projectionList.add(MediaStore.Audio.Media.RELATIVE_PATH)
            }

            var cursor: android.database.Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projectionList.toTypedArray(),
                    null,
                    null,
                    "${MediaStore.Audio.Media.TITLE} ASC"
                )
            } catch (_: Exception) {
                // Safe fallback without DATA / RELATIVE_PATH if vendor throws column exception
                try {
                    val safeProjection = arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.ALBUM_ID,
                        MediaStore.Audio.Media.ARTIST_ID,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.YEAR,
                        MediaStore.Audio.Media.TRACK,
                        MediaStore.Audio.Media.DATE_ADDED,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.MIME_TYPE
                    )
                    cursor = context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        safeProjection,
                        null,
                        null,
                        "${MediaStore.Audio.Media.TITLE} ASC"
                    )
                } catch (_: Exception) {}
            }

            cursor?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val artistIdCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID)
                val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val yearCol = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val dateAddedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1

                val totalInCursor = c.count
                var processedSoFar = 0

                while (c.moveToNext()) {
                    processedSoFar++

                    val id = if (idCol >= 0) c.getLong(idCol) else System.currentTimeMillis()
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()

                    val displayName = if (nameCol >= 0) {
                        try { c.getString(nameCol) } catch (_: Exception) { null }
                    } else null

                    val relPath = if (relPathCol >= 0) {
                        try { c.getString(relPathCol) } catch (_: Exception) { null }
                    } else null

                    var rawPath = if (dataCol >= 0) {
                        try { c.getString(dataCol) } catch (_: Exception) { null }
                    } else null

                    if (rawPath.isNullOrBlank()) {
                        rawPath = when {
                            !relPath.isNullOrBlank() && !displayName.isNullOrBlank() -> {
                                File(Environment.getExternalStorageDirectory(), "${relPath.trim('/')}/$displayName").absolutePath
                            }
                            !displayName.isNullOrBlank() -> {
                                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), displayName).absolutePath
                            }
                            else -> "/storage/emulated/0/Music/audio_$id.mp3"
                        }
                    }
                    val filePath = rawPath

                    val parentFolder = when {
                        !relPath.isNullOrBlank() -> {
                            File(Environment.getExternalStorageDirectory(), relPath.trim('/')).absolutePath
                        }
                        filePath.startsWith("/") -> {
                            File(filePath).parent?.trimEnd('/') ?: ""
                        }
                        else -> ""
                    }

                    // 1. Fast Folder Exclusions Check
                    if (parentFolder.isNotBlank() && isNormPathExcluded(normalizePath(parentFolder), normExcludedPaths)) {
                        continue
                    }

                    // 2. Fast Folder Inclusions Check (if any specified by user)
                    if (normIncludedPaths.isNotEmpty()) {
                        val normParent = normalizePath(parentFolder)
                        val isIncluded = isSongInIncludedFolders(normParent, normIncludedPaths) ||
                                (!relPath.isNullOrBlank() && isRelPathInIncludedFolders(normalizePath(relPath), normIncludedPaths))
                        if (!isIncluded) continue
                    }

                    // Audio validation: Check MIME type and extensions
                    val mimeType = if (mimeCol >= 0) {
                        try { c.getString(mimeCol) } catch (_: Exception) { null }
                    } else null

                    val ext = when {
                        !displayName.isNullOrBlank() && displayName.contains('.') -> displayName.substringAfterLast('.').lowercase()
                        filePath.contains('.') -> filePath.substringAfterLast('.').lowercase()
                        else -> ""
                    }

                    val isAudioMime = mimeType?.startsWith("audio/", ignoreCase = true) == true ||
                            mimeType.equals("application/ogg", ignoreCase = true)
                    val isAudioExt = ext in AUDIO_EXTENSIONS || AUDIO_EXTENSIONS.any { filePath.lowercase().endsWith(".$it") }

                    if (!isAudioMime && !isAudioExt && ext.isNotBlank() && !ext.equals("mp3", ignoreCase = true)) {
                        continue
                    }

                    val duration = if (durationCol >= 0) c.getLong(durationCol) else 0L
                    if (duration > 0 && duration < ignoreAudioDurationMs) continue

                    val rawTitle = if (titleCol >= 0) c.getString(titleCol) else null
                    val rawArtist = if (artistCol >= 0) c.getString(artistCol) else null
                    val rawAlbum = if (albumCol >= 0) c.getString(albumCol) else null
                    val albumId = if (albumIdCol >= 0) c.getLong(albumIdCol) else 0L
                    val artistId = if (artistIdCol >= 0) c.getLong(artistIdCol) else 0L
                    val year = if (yearCol >= 0) c.getInt(yearCol) else 0
                    val track = if (trackCol >= 0) c.getInt(trackCol) else 0
                    val dateAdded = if (dateAddedCol >= 0) c.getLong(dateAddedCol) else System.currentTimeMillis() / 1000

                    val filenameForTag = displayName ?: File(filePath).name
                    val cleanedTitle = TagCleaner.cleanTitle(rawTitle, filenameForTag)
                    val cleanedArtist = TagCleaner.cleanArtist(rawArtist)
                    val cleanedAlbum = TagCleaner.cleanAlbum(rawAlbum)
                    val format = ext.ifBlank { "MP3" }.uppercase()

                    val song = SongEntity(
                        id = id,
                        title = cleanedTitle,
                        artist = cleanedArtist,
                        album = cleanedAlbum,
                        genre = "Music",
                        durationMs = if (duration > 0) duration else 180000L,
                        path = filePath,
                        contentUriString = contentUri,
                        artworkUri = null,
                        albumId = albumId,
                        artistId = artistId,
                        trackNumber = track,
                        year = year,
                        dateAdded = dateAdded * 1000,
                        format = format,
                        isMissing = false
                    )

                    val dedupeKey = if (id > 0) "id_$id" else "path_${normalizePath(filePath)}"
                    foundSongsMap[dedupeKey] = song

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime > 60 || processedSoFar == totalInCursor || processedSoFar == 1) {
                        lastEmitTime = now
                        val folderName = if (parentFolder.isNotBlank()) {
                            parentFolder.substringAfterLast('/').ifBlank { "Device Audio" }
                        } else "Device Audio"
                        _scanProgress.value = ScanProgress(
                            isScanning = true,
                            totalDiscovered = totalInCursor,
                            processedCount = foundSongsMap.size,
                            currentFolder = folderName,
                            isFinished = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Direct Storage Traversal with Reusable Metadata Retriever
        val retriever = MediaMetadataRetriever()
        try {
            val rootsToScan = mutableListOf<File>()
            if (normIncludedPaths.isNotEmpty()) {
                includedFolders.forEach { folder ->
                    val dir = File(folder.path)
                    if (dir.exists() && dir.isDirectory) rootsToScan.add(dir)
                }
            } else {
                val publicDirs = mutableListOf(
                    Environment.DIRECTORY_MUSIC,
                    Environment.DIRECTORY_DOWNLOADS,
                    Environment.DIRECTORY_PODCASTS,
                    Environment.DIRECTORY_DOCUMENTS
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    publicDirs.add(Environment.DIRECTORY_RECORDINGS)
                }
                for (dirType in publicDirs) {
                    val pDir = Environment.getExternalStoragePublicDirectory(dirType)
                    if (pDir != null && pDir.exists() && pDir.isDirectory && !rootsToScan.contains(pDir)) {
                        rootsToScan.add(pDir)
                    }
                }

                // Add direct subdirectories of External Storage (e.g. Music, Songs, Audio, Downloads, etc.)
                try {
                    val extDir = Environment.getExternalStorageDirectory()
                    if (extDir != null && extDir.exists() && extDir.isDirectory) {
                        extDir.listFiles()?.forEach { sub ->
                            if (sub.isDirectory && !sub.name.startsWith(".") &&
                                !sub.name.equals("Android", ignoreCase = true) &&
                                !sub.name.equals("cache", ignoreCase = true) &&
                                !sub.name.equals("LOST.DIR", ignoreCase = true) &&
                                !rootsToScan.contains(sub)
                            ) {
                                rootsToScan.add(sub)
                            }
                        }
                    }
                } catch (_: Exception) {}

                // Also check secondary/SD card storage paths if available
                try {
                    val externalDirs = androidx.core.content.ContextCompat.getExternalFilesDirs(context, null)
                    for (file in externalDirs) {
                        if (file != null) {
                            val path = file.absolutePath
                            val androidIdx = path.indexOf("/Android")
                            if (androidIdx > 0) {
                                val rootDir = File(path.substring(0, androidIdx))
                                if (rootDir.exists() && rootDir.isDirectory && !rootsToScan.contains(rootDir)) {
                                    rootsToScan.add(rootDir)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            for (root in rootsToScan) {
                scanDirectoryRecursivelyFast(
                    directory = root,
                    normIncludedPaths = normIncludedPaths,
                    normExcludedPaths = normExcludedPaths,
                    ignoreAudioDurationMs = ignoreAudioDurationMs,
                    foundSongsMap = foundSongsMap,
                    retriever = retriever,
                    onProgress = { currentDir, count ->
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime > 80) {
                            lastEmitTime = now
                            _scanProgress.value = ScanProgress(
                                isScanning = true,
                                totalDiscovered = count,
                                processedCount = count,
                                currentFolder = currentDir,
                                isFinished = false
                            )
                        }
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // 3. Save Discovered Songs to DB with State Preservation
        val rawSongsList = foundSongsMap.values.toList()
        _scanProgress.value = ScanProgress(
            isScanning = true,
            totalDiscovered = rawSongsList.size,
            processedCount = rawSongsList.size,
            currentFolder = "Saving library (${rawSongsList.size} tracks)...",
            isFinished = false
        )

        try {
            if (rawSongsList.isNotEmpty()) {
                val existingSongs = songDao.getAllSongsOnce()
                val existingById = existingSongs.associateBy { it.id }
                val existingByPath = existingSongs.associateBy { normalizePath(it.path) }

                val mergedSongsList = rawSongsList.map { song ->
                    val normP = normalizePath(song.path)
                    val existing = existingByPath[normP] ?: existingById[song.id]
                    val manualArt = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, existing?.id ?: song.id, song.path)

                    if (existing != null) {
                        song.copy(
                            id = existing.id,
                            isFavorite = existing.isFavorite,
                            playCount = existing.playCount,
                            lastPlayed = existing.lastPlayed,
                            title = if (existing.title.isNotBlank() && !existing.title.endsWith(".mp3", ignoreCase = true) && !existing.title.endsWith(".flac", ignoreCase = true)) existing.title else song.title,
                            artist = if (existing.artist.isNotBlank() && existing.artist != "Unknown Artist") existing.artist else song.artist,
                            album = if (existing.album.isNotBlank() && existing.album != "Unknown Album") existing.album else song.album,
                            artworkUri = manualArt ?: existing.artworkUri ?: song.artworkUri
                        )
                    } else {
                        if (manualArt != null) song.copy(artworkUri = manualArt) else song
                    }
                }

                // Delete only unused sample songs without wiping user favorites or play history
                songDao.deleteUnusedSampleSongs()
                songDao.insertSongs(mergedSongsList)

                // 4. Mark or remove missing songs for real scans (fast check)
                val updatedSongs = songDao.getAllSongsOnce()
                updatedSongs.forEach { dbSong ->
                    if (!dbSong.path.startsWith("sample_") && !dbSong.contentUriString.startsWith("http")) {
                        val parentFolder = File(dbSong.path).parent?.trimEnd('/') ?: ""
                        val normParent = normalizePath(parentFolder)
                        val isExcluded = isNormPathExcluded(normParent, normExcludedPaths)
                        val isNotIncluded = !isSongInIncludedFolders(normParent, normIncludedPaths)
                        
                        val isContentUri = dbSong.contentUriString.startsWith("content://")
                        val fileObj = File(dbSong.path)
                        val isDirectFileAccessible = try { fileObj.exists() } catch (_: Exception) { false }
                        val isAccessible = if (isContentUri) true else isDirectFileAccessible
                        val isTooShort = ignoreAudioDurationMs > 0 && dbSong.durationMs > 0 && dbSong.durationMs < ignoreAudioDurationMs
                        val fileMissing = isExcluded || isNotIncluded || !isAccessible || isTooShort

                        if (fileMissing) {
                            songDao.markMissing(dbSong.path)
                        }
                    }
                }
                songDao.deleteMissingSongs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val finalCount = songDao.getSongCountOnce()
        _scanProgress.value = ScanProgress(
            isScanning = false,
            totalDiscovered = finalCount,
            processedCount = finalCount,
            currentFolder = "",
            isFinished = true
        )
    }

    private suspend fun scanDirectoryRecursivelyFast(
        directory: File,
        normIncludedPaths: List<String>,
        normExcludedPaths: List<String>,
        ignoreAudioDurationMs: Long,
        foundSongsMap: MutableMap<String, SongEntity>,
        retriever: MediaMetadataRetriever,
        onProgress: ((String, Int) -> Unit)? = null
    ) {
        val parentFolder = directory.absolutePath.trimEnd('/')
        val normParent = normalizePath(parentFolder)
        if (isNormPathExcluded(normParent, normExcludedPaths)) return

        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                val dirName = file.name
                if (dirName.startsWith(".") ||
                    dirName.equals("Android", ignoreCase = true) ||
                    dirName.equals("cache", ignoreCase = true) ||
                    dirName.equals("thumbnails", ignoreCase = true) ||
                    dirName.equals("lost+found", ignoreCase = true) ||
                    dirName.equals("temp", ignoreCase = true) ||
                    dirName.equals("tmp", ignoreCase = true)
                ) {
                    continue
                }
                if (!isDirInIncludedFolders(normalizePath(file.absolutePath), normIncludedPaths)) continue
                scanDirectoryRecursivelyFast(file, normIncludedPaths, normExcludedPaths, ignoreAudioDurationMs, foundSongsMap, retriever, onProgress)
            } else if (file.isFile) {
                val ext = file.extension.lowercase()
                if (ext in AUDIO_EXTENSIONS) {
                    val filePath = file.absolutePath
                    val normPath = filePath.lowercase()
                    if (!foundSongsMap.containsKey(normPath)) {
                        val fileParent = file.parent?.trimEnd('/') ?: ""
                        val normFileParent = normalizePath(fileParent)
                        if (isNormPathExcluded(normFileParent, normExcludedPaths)) continue
                        if (!isSongInIncludedFolders(normFileParent, normIncludedPaths)) continue

                        var duration = 0L
                        var rawTitle: String? = null
                        var rawArtist: String? = null
                        var rawAlbum: String? = null

                        try {
                            retriever.setDataSource(filePath)
                            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        } catch (_: Throwable) {
                            try {
                                val localRetriever = MediaMetadataRetriever()
                                try {
                                    localRetriever.setDataSource(filePath)
                                    duration = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    rawTitle = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                    rawArtist = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                    rawAlbum = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                } finally {
                                    try { localRetriever.release() } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                        }

                        if (duration > 0 && duration < ignoreAudioDurationMs) continue

                        val cleanedTitle = TagCleaner.cleanTitle(rawTitle, file.name)
                        val cleanedArtist = TagCleaner.cleanArtist(rawArtist)
                        val cleanedAlbum = TagCleaner.cleanAlbum(rawAlbum)
                        val fileId = filePath.hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL

                        val song = SongEntity(
                            id = fileId,
                            title = cleanedTitle,
                            artist = cleanedArtist,
                            album = cleanedAlbum,
                            genre = "Music",
                            durationMs = if (duration > 0) duration else 180000L,
                            path = filePath,
                            contentUriString = Uri.fromFile(file).toString(),
                            artworkUri = null,
                            albumId = 0L,
                            artistId = 0L,
                            trackNumber = 0,
                            year = 0,
                            dateAdded = file.lastModified(),
                            format = ext.uppercase(),
                            isMissing = false
                        )
                        foundSongsMap[normPath] = song
                        onProgress?.invoke(directory.name, foundSongsMap.size)
                        try {
                            MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun normalizePath(rawPath: String): String {
        var p = rawPath.trim().replace('\\', '/')
        while (p.contains("//")) {
            p = p.replace("//", "/")
        }
        p = p.trimEnd('/')
        p = p.replace("/sdcard", "/storage/emulated/0", ignoreCase = true)
        p = p.replace("/storage/self/primary", "/storage/emulated/0", ignoreCase = true)
        p = p.replace("/mnt/sdcard", "/storage/emulated/0", ignoreCase = true)
        return p.lowercase()
    }

    private fun isNormPathExcluded(normParent: String, normExcludedPaths: List<String>): Boolean {
        if (normExcludedPaths.isEmpty()) return false
        return normExcludedPaths.any { normExcluded ->
            normParent == normExcluded || normParent.startsWith("$normExcluded/")
        }
    }

    private fun isSongInIncludedFolders(normParent: String, normIncludedPaths: List<String>): Boolean {
        if (normIncludedPaths.isEmpty()) return true
        return normIncludedPaths.any { normIncluded ->
            normParent == normIncluded || normParent.startsWith("$normIncluded/")
        }
    }

    private fun isRelPathInIncludedFolders(relPath: String, normIncludedPaths: List<String>): Boolean {
        if (normIncludedPaths.isEmpty()) return true
        val fullSynthetic = normalizePath("/storage/emulated/0/${relPath.trim('/')}")
        return normIncludedPaths.any { normIncluded ->
            fullSynthetic == normIncluded || fullSynthetic.startsWith("$normIncluded/") || normIncluded.startsWith("$fullSynthetic/")
        }
    }

    private fun isDirInIncludedFolders(normDir: String, normIncludedPaths: List<String>): Boolean {
        if (normIncludedPaths.isEmpty()) return true
        return normIncludedPaths.any { normIncluded ->
            normDir == normIncluded || normDir.startsWith("$normIncluded/") || normIncluded.startsWith("$normDir/")
        }
    }

    suspend fun quickScanAndCleanLibrary(
        includedFolders: List<FolderEntity> = emptyList(),
        excludedFolders: List<FolderEntity> = emptyList(),
        ignoreAudioDurationMs: Long = 0L
    ) = withContext(Dispatchers.IO) {
        _scanProgress.value = ScanProgress(
            isScanning = true,
            totalDiscovered = 0,
            processedCount = 0,
            currentFolder = "Quick indexing...",
            isFinished = false
        )

        val normExcludedPaths = excludedFolders.map { normalizePath(it.path) }
        val normIncludedPaths = includedFolders.map { normalizePath(it.path) }
        var lastEmitTime = 0L

        try {
            // 1. Clean missing or excluded songs from DB without wiping tags/lyrics/favorites
            val existingSongs = songDao.getAllSongsOnce()
            val existingByPath = existingSongs.associateBy { normalizePath(it.path) }
            val existingById = existingSongs.associateBy { it.id }

            var cleanedCount = 0
            existingSongs.forEach { dbSong ->
                if (!dbSong.path.startsWith("sample_") && !dbSong.contentUriString.startsWith("http")) {
                    val parentFolder = File(dbSong.path).parent?.trimEnd('/') ?: ""
                    val normParent = normalizePath(parentFolder)
                    val isExcluded = isNormPathExcluded(normParent, normExcludedPaths)
                    val isNotIncluded = !isSongInIncludedFolders(normParent, normIncludedPaths)

                    val isContentUri = dbSong.contentUriString.startsWith("content://")
                    val fileObj = File(dbSong.path)
                    val isDirectFileAccessible = try { fileObj.exists() } catch (_: Exception) { false }
                    val isAccessible = if (isContentUri) true else isDirectFileAccessible
                    val isTooShort = ignoreAudioDurationMs > 0 && dbSong.durationMs > 0 && dbSong.durationMs < ignoreAudioDurationMs
                    val fileMissing = isExcluded || isNotIncluded || !isAccessible || isTooShort

                    if (fileMissing) {
                        songDao.markMissing(dbSong.path)
                        cleanedCount++
                    }
                }
            }
            songDao.deleteMissingSongs()

            // 2. Resilient MediaStore Scan for newly added songs not yet in DB
            val newSongsList = mutableListOf<SongEntity>()

            val projectionList = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATA
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projectionList.add(MediaStore.Audio.Media.RELATIVE_PATH)
            }

            var cursor: android.database.Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projectionList.toTypedArray(),
                    null,
                    null,
                    "${MediaStore.Audio.Media.TITLE} ASC"
                )
            } catch (_: Exception) {
                try {
                    val safeProjection = arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.ALBUM_ID,
                        MediaStore.Audio.Media.ARTIST_ID,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.YEAR,
                        MediaStore.Audio.Media.TRACK,
                        MediaStore.Audio.Media.DATE_ADDED,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.MIME_TYPE
                    )
                    cursor = context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        safeProjection,
                        null,
                        null,
                        "${MediaStore.Audio.Media.TITLE} ASC"
                    )
                } catch (_: Exception) {}
            }

            cursor?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val artistIdCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID)
                val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val yearCol = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val dateAddedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1

                val totalInCursor = c.count
                var processedInCursor = 0

                while (c.moveToNext()) {
                    processedInCursor++

                    val mediaStoreId = if (idCol >= 0) c.getLong(idCol) else System.currentTimeMillis()
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)

                    val displayName = if (nameCol >= 0) {
                        try { c.getString(nameCol) } catch (_: Exception) { null }
                    } else null

                    val relPath = if (relPathCol >= 0) {
                        try { c.getString(relPathCol) } catch (_: Exception) { null }
                    } else null

                    var rawPath = if (dataCol >= 0) {
                        try { c.getString(dataCol) } catch (_: Exception) { null }
                    } else null

                    if (rawPath.isNullOrBlank()) {
                        rawPath = when {
                            !relPath.isNullOrBlank() && !displayName.isNullOrBlank() -> {
                                File(Environment.getExternalStorageDirectory(), "${relPath.trim('/')}/$displayName").absolutePath
                            }
                            !displayName.isNullOrBlank() -> {
                                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), displayName).absolutePath
                            }
                            else -> "/storage/emulated/0/Music/audio_$mediaStoreId.mp3"
                        }
                    }
                    val filePath = rawPath
                    val normP = normalizePath(filePath)

                    // Skip if already indexed in database
                    if (existingByPath.containsKey(normP) || existingById.containsKey(mediaStoreId)) continue

                    val parentFolder = when {
                        !relPath.isNullOrBlank() -> {
                            File(Environment.getExternalStorageDirectory(), relPath.trim('/')).absolutePath
                        }
                        filePath.startsWith("/") -> {
                            File(filePath).parent?.trimEnd('/') ?: ""
                        }
                        else -> ""
                    }

                    if (parentFolder.isNotBlank() && isNormPathExcluded(normalizePath(parentFolder), normExcludedPaths)) continue

                    if (normIncludedPaths.isNotEmpty()) {
                        val normParent = normalizePath(parentFolder)
                        val isIncluded = isSongInIncludedFolders(normParent, normIncludedPaths) ||
                                (!relPath.isNullOrBlank() && isRelPathInIncludedFolders(normalizePath(relPath), normIncludedPaths))
                        if (!isIncluded) continue
                    }

                    // Validate audio type
                    val mimeType = if (mimeCol >= 0) {
                        try { c.getString(mimeCol) } catch (_: Exception) { null }
                    } else null

                    val ext = when {
                        !displayName.isNullOrBlank() && displayName.contains('.') -> displayName.substringAfterLast('.').lowercase()
                        filePath.contains('.') -> filePath.substringAfterLast('.').lowercase()
                        else -> ""
                    }

                    val isAudioMime = mimeType?.startsWith("audio/", ignoreCase = true) == true ||
                            mimeType.equals("application/ogg", ignoreCase = true)
                    val isAudioExt = ext in AUDIO_EXTENSIONS || AUDIO_EXTENSIONS.any { filePath.lowercase().endsWith(".$it") }

                    if (!isAudioMime && !isAudioExt && ext.isNotBlank() && !ext.equals("mp3", ignoreCase = true)) {
                        continue
                    }

                    val dur = if (durationCol >= 0) c.getLong(durationCol) else 0L
                    if (dur < ignoreAudioDurationMs && dur > 0L) continue

                    val rawTitle = if (titleCol >= 0) c.getString(titleCol) else ""
                    val rawArtist = if (artistCol >= 0) c.getString(artistCol) else ""
                    val rawAlbum = if (albumCol >= 0) c.getString(albumCol) else ""
                    val filename = displayName ?: File(filePath).name

                    val cleanTitle = TagCleaner.cleanTitle(rawTitle, filename)
                    val cleanArtist = TagCleaner.cleanArtist(rawArtist)
                    val cleanAlbum = TagCleaner.cleanAlbum(rawAlbum)
                    val albumId = if (albumIdCol >= 0) c.getLong(albumIdCol) else 0L
                    val artistId = if (artistIdCol >= 0) c.getLong(artistIdCol) else 0L
                    val year = if (yearCol >= 0) c.getInt(yearCol) else 0
                    val trackNum = if (trackCol >= 0) c.getInt(trackCol) else 0
                    val dateAdded = if (dateAddedCol >= 0) c.getLong(dateAddedCol) else System.currentTimeMillis() / 1000

                    val manualArt = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, mediaStoreId, filePath)
                    val artworkUri: String? = manualArt ?: try {
                        com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, mediaStoreId, filePath)
                    } catch (_: Exception) { null }

                    val format = ext.ifBlank { "MP3" }.uppercase()

                    newSongsList.add(
                        SongEntity(
                            id = mediaStoreId,
                            title = cleanTitle,
                            artist = cleanArtist,
                            album = cleanAlbum,
                            genre = "Music",
                            durationMs = if (dur > 0) dur else 180000L,
                            path = filePath,
                            contentUriString = uri.toString(),
                            artworkUri = artworkUri,
                            albumId = albumId,
                            artistId = artistId,
                            trackNumber = trackNum,
                            year = year,
                            dateAdded = dateAdded * 1000,
                            format = format,
                            isMissing = false
                        )
                    )

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime > 80 || processedInCursor == totalInCursor || processedInCursor == 1) {
                        lastEmitTime = now
                        val folderName = if (parentFolder.isNotBlank()) {
                            parentFolder.substringAfterLast('/').ifBlank { "Storage" }
                        } else "Storage"
                        _scanProgress.value = ScanProgress(
                            isScanning = true,
                            totalDiscovered = totalInCursor,
                            processedCount = newSongsList.size,
                            currentFolder = folderName,
                            isFinished = false
                        )
                    }
                }
            }

            // 3. Fast direct check on common storage music directories for files not yet picked up by MediaStore
            try {
                val fastScanDirs = mutableListOf<File>()
                if (normIncludedPaths.isNotEmpty()) {
                    includedFolders.forEach { f ->
                        val dir = File(f.path)
                        if (dir.exists() && dir.isDirectory) fastScanDirs.add(dir)
                    }
                } else {
                    val publicDirs = listOf(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
                        File(Environment.getExternalStorageDirectory(), "Music"),
                        File(Environment.getExternalStorageDirectory(), "Download"),
                        File(Environment.getExternalStorageDirectory(), "Downloads"),
                        File(Environment.getExternalStorageDirectory(), "Songs"),
                        File(Environment.getExternalStorageDirectory(), "Audio")
                    )
                    publicDirs.forEach { dir ->
                        if (dir != null && dir.exists() && dir.isDirectory && !fastScanDirs.contains(dir)) {
                            fastScanDirs.add(dir)
                        }
                    }
                }

                val discoveredPaths = newSongsList.map { normalizePath(it.path) }.toMutableSet()
                for (dir in fastScanDirs) {
                    scanDirectDirectoryForNewSongs(
                        directory = dir,
                        maxDepth = 3,
                        existingByPath = existingByPath,
                        discoveredPaths = discoveredPaths,
                        normIncludedPaths = normIncludedPaths,
                        normExcludedPaths = normExcludedPaths,
                        ignoreAudioDurationMs = ignoreAudioDurationMs,
                        newSongsList = newSongsList,
                        onProgress = { currentDir, count ->
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime > 80) {
                                lastEmitTime = now
                                _scanProgress.value = ScanProgress(
                                    isScanning = true,
                                    totalDiscovered = count,
                                    processedCount = count,
                                    currentFolder = currentDir,
                                    isFinished = false
                                )
                            }
                        }
                    )
                }
            } catch (_: Exception) {}

            if (newSongsList.isNotEmpty()) {
                songDao.insertSongs(newSongsList)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val totalCount = songDao.getSongCountOnce()
        _scanProgress.value = ScanProgress(
            isScanning = false,
            totalDiscovered = totalCount,
            processedCount = totalCount,
            currentFolder = "",
            isFinished = true
        )
    }

    private fun scanDirectDirectoryForNewSongs(
        directory: File,
        maxDepth: Int,
        existingByPath: Map<String, SongEntity>,
        discoveredPaths: MutableSet<String>,
        normIncludedPaths: List<String>,
        normExcludedPaths: List<String>,
        ignoreAudioDurationMs: Long,
        newSongsList: MutableList<SongEntity>,
        onProgress: ((String, Int) -> Unit)? = null
    ) {
        if (maxDepth <= 0 || !directory.exists() || !directory.isDirectory) return
        val parentFolder = directory.absolutePath.trimEnd('/')
        val normParent = normalizePath(parentFolder)
        if (isNormPathExcluded(normParent, normExcludedPaths)) return

        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                val dirName = file.name
                if (dirName.startsWith(".") ||
                    dirName.equals("Android", ignoreCase = true) ||
                    dirName.equals("cache", ignoreCase = true) ||
                    dirName.equals("thumbnails", ignoreCase = true) ||
                    dirName.equals("lost+found", ignoreCase = true) ||
                    dirName.equals("LOST.DIR", ignoreCase = true)
                ) continue
                if (!isDirInIncludedFolders(normalizePath(file.absolutePath), normIncludedPaths)) continue
                scanDirectDirectoryForNewSongs(
                    directory = file,
                    maxDepth = maxDepth - 1,
                    existingByPath = existingByPath,
                    discoveredPaths = discoveredPaths,
                    normIncludedPaths = normIncludedPaths,
                    normExcludedPaths = normExcludedPaths,
                    ignoreAudioDurationMs = ignoreAudioDurationMs,
                    newSongsList = newSongsList,
                    onProgress = onProgress
                )
            } else if (file.isFile) {
                val ext = file.extension.lowercase()
                if (ext in AUDIO_EXTENSIONS) {
                    val filePath = file.absolutePath
                    val normPath = normalizePath(filePath)
                    if (!existingByPath.containsKey(normPath) && !discoveredPaths.contains(normPath)) {
                        val fileParent = file.parent?.trimEnd('/') ?: ""
                        val normFileParent = normalizePath(fileParent)
                        if (isNormPathExcluded(normFileParent, normExcludedPaths)) continue
                        if (!isSongInIncludedFolders(normFileParent, normIncludedPaths)) continue

                        discoveredPaths.add(normPath)
                        var duration = 0L
                        var rawTitle: String? = null
                        var rawArtist: String? = null
                        var rawAlbum: String? = null

                        val localRetriever = MediaMetadataRetriever()
                        try {
                            localRetriever.setDataSource(filePath)
                            duration = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            rawTitle = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            rawArtist = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            rawAlbum = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        } catch (_: Throwable) {
                        } finally {
                            try { localRetriever.release() } catch (_: Throwable) {}
                        }

                        if (duration > 0 && duration < ignoreAudioDurationMs) continue

                        val cleanedTitle = TagCleaner.cleanTitle(rawTitle, file.name)
                        val cleanedArtist = TagCleaner.cleanArtist(rawArtist)
                        val cleanedAlbum = TagCleaner.cleanAlbum(rawAlbum)
                        val fileId = filePath.hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL

                        val song = SongEntity(
                            id = fileId,
                            title = cleanedTitle,
                            artist = cleanedArtist,
                            album = cleanedAlbum,
                            genre = "Music",
                            durationMs = if (duration > 0) duration else 180000L,
                            path = filePath,
                            contentUriString = Uri.fromFile(file).toString(),
                            artworkUri = null,
                            albumId = 0L,
                            artistId = 0L,
                            trackNumber = 0,
                            year = 0,
                            dateAdded = file.lastModified(),
                            format = ext.uppercase(),
                            isMissing = false
                        )
                        newSongsList.add(song)
                        onProgress?.invoke(directory.name, newSongsList.size)

                        try {
                            MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }
}
