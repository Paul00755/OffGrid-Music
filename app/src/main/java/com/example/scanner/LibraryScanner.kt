package com.example.scanner

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.example.data.local.FolderDao
import com.example.data.local.FolderEntity
import com.example.data.local.SongDao
import com.example.data.local.SongEntity
import com.example.data.local.UserPreferencesRepository
import com.example.util.TagCleaner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class ScanStatus {
    IDLE,
    SCANNING,
    COMPLETED,
    ERROR
}

data class ScanProgress(
    val status: ScanStatus = ScanStatus.IDLE,
    val filesDiscovered: Int = 0,
    val filesProcessed: Int = 0,
    val validTracksFound: Int = 0,
    val duplicatesSkipped: Int = 0,
    val filteredRejected: Int = 0,
    val currentFolder: String = "",
    val errorMessage: String? = null,
    val isScanning: Boolean = (status == ScanStatus.SCANNING),
    val totalDiscovered: Int = validTracksFound,
    val processedCount: Int = validTracksFound,
    val isFinished: Boolean = (status == ScanStatus.COMPLETED),
    val error: String? = errorMessage
) {
    val displayTracksCount: Int
        get() = validTracksFound

    val isEffectivelyScanning: Boolean
        get() = status == ScanStatus.SCANNING || isScanning
}

class LibraryScanner(
    private val context: Context,
    private val songDao: SongDao,
    private val folderDao: FolderDao? = null
) {
    companion object {
        private const val TAG = "LibraryScanner"
        private const val SQLITE_BATCH_SIZE = 250
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L

        @Volatile
        private var INSTANCE: LibraryScanner? = null

        fun getInstance(context: Context, songDao: SongDao, folderDao: FolderDao? = null): LibraryScanner {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryScanner(context.applicationContext, songDao, folderDao).also {
                    INSTANCE = it
                }
            }
        }

        /**
         * Computes a collision-free 62-bit positive synthetic ID for files discovered outside MediaStore
         * (placed above 1_000_000_000_000L so they never collide with 32-bit MediaStore IDs).
         */
        fun computeStablePathId(normPath: String): Long {
            var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
            for (i in normPath.indices) {
                hash = hash xor normPath[i].code.toLong()
                hash *= 0x100000001b3L // FNV-1a 64-bit prime
            }
            val positive = (hash and 0x3FFFFFFF_FFFFFFFFL).coerceAtLeast(1L)
            return 1_000_000_000_000L + positive
        }
    }

    /**
     * Authoritative single pipeline for creating or updating SongEntity instances.
     */
    fun buildOrUpdateSongEntity(
        existing: SongEntity?,
        id: Long,
        rawTitle: String?,
        rawArtist: String?,
        rawAlbum: String?,
        durationMs: Long,
        filePath: String,
        contentUri: String,
        albumId: Long = 0L,
        artistId: Long = 0L,
        trackNum: Int = 0,
        year: Int = 0,
        dateAdded: Long = System.currentTimeMillis(),
        format: String = "MP3"
    ): SongEntity {
        val filename = File(filePath).name
        val resolvedId = existing?.id ?: id
        val manualMeta = com.example.util.ManualTagStore.getManualMetadata(context, resolvedId, filePath)
        val isManual = existing?.isManuallyEdited == true ||
            (existing != null && com.example.util.ManualTagStore.isManuallyEdited(context, existing.id, existing.path)) ||
            manualMeta != null

        val manualArt = manualMeta?.artworkUri
            ?: com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, resolvedId, filePath)
        val finalArtwork = manualArt ?: existing?.artworkUri?.takeIf { it.isNotBlank() } ?: try {
            com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, resolvedId, filePath)
        } catch (_: Exception) { null }

        if (isManual) {
            val resolvedTitle = manualMeta?.title?.takeIf { it.isNotBlank() } ?: (if (existing?.isManuallyEdited == true) existing.title else rawTitle) ?: filename.substringBeforeLast(".")
            val resolvedArtist = manualMeta?.artist?.takeIf { it.isNotBlank() } ?: (if (existing?.isManuallyEdited == true) existing.artist else rawArtist) ?: "Unknown Artist"
            val resolvedAlbum = manualMeta?.album?.takeIf { it.isNotBlank() } ?: (if (existing?.isManuallyEdited == true) existing.album else rawAlbum) ?: "Unknown Album"
            val resolvedGenre = manualMeta?.genre ?: existing?.genre ?: "Music"
            val resolvedYear = manualMeta?.year ?: (if (year > 0) year else (existing?.year ?: 0))
            val resolvedTrack = manualMeta?.trackNumber ?: (if (trackNum > 0) trackNum else (existing?.trackNumber ?: 0))

            return SongEntity(
                id = resolvedId,
                title = resolvedTitle,
                artist = resolvedArtist,
                album = resolvedAlbum,
                genre = resolvedGenre,
                durationMs = if (durationMs > 0) durationMs else (existing?.durationMs ?: 180000L),
                path = filePath,
                contentUriString = contentUri,
                artworkUri = finalArtwork,
                albumId = if (albumId > 0) albumId else (existing?.albumId ?: 0L),
                artistId = if (artistId > 0) artistId else (existing?.artistId ?: 0L),
                trackNumber = resolvedTrack,
                year = resolvedYear,
                dateAdded = existing?.dateAdded ?: dateAdded,
                lastPlayed = existing?.lastPlayed ?: 0L,
                playCount = existing?.playCount ?: 0,
                isFavorite = existing?.isFavorite ?: false,
                bitrate = existing?.bitrate ?: 0,
                format = format,
                isMissing = false,
                isCorrupted = false,
                isManuallyEdited = true
            )
        }

        val cleanTitle = TagCleaner.cleanTitle(rawTitle, filename)
        val cleanArtist = TagCleaner.cleanArtist(rawArtist)
        val cleanAlbum = TagCleaner.cleanAlbum(rawAlbum)

        return SongEntity(
            id = resolvedId,
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
            genre = existing?.genre ?: "Music",
            durationMs = if (durationMs > 0) durationMs else (existing?.durationMs ?: 180000L),
            path = filePath,
            contentUriString = contentUri,
            artworkUri = finalArtwork,
            albumId = if (albumId > 0) albumId else (existing?.albumId ?: 0L),
            artistId = if (artistId > 0) artistId else (existing?.artistId ?: 0L),
            trackNumber = if (trackNum > 0) trackNum else (existing?.trackNumber ?: 0),
            year = if (year > 0) year else (existing?.year ?: 0),
            dateAdded = existing?.dateAdded ?: dateAdded,
            lastPlayed = existing?.lastPlayed ?: 0L,
            playCount = existing?.playCount ?: 0,
            isFavorite = existing?.isFavorite ?: false,
            bitrate = existing?.bitrate ?: 0,
            format = format,
            isMissing = false,
            isManuallyEdited = false
        )
    }

    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: Flow<ScanProgress> = _scanProgress.asStateFlow()

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "amr", "3gp", "m4r", "aiff"
    )

    private val scanMutex = Mutex()
    private var pendingAutoDetect = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var contentObserver: ContentObserver? = null
    private val activeFileObservers = mutableListOf<FileObserver>()
    private var debounceJob: Job? = null
    var onScanCompletedListener: (() -> Unit)? = null

    private suspend fun insertSongsSafelyInBatches(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        for (chunk in songs.chunked(SQLITE_BATCH_SIZE)) {
            songDao.insertSongs(chunk)
        }
    }

    private suspend fun updateSongsSafelyInBatches(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        for (chunk in songs.chunked(SQLITE_BATCH_SIZE)) {
            songDao.updateSongs(chunk)
        }
    }

    private suspend fun deleteSongsSafelyInBatches(ids: List<Long>) {
        if (ids.isEmpty()) return
        for (chunk in ids.chunked(SQLITE_BATCH_SIZE)) {
            songDao.deleteSongsByIds(chunk)
        }
    }

    private fun resolveGenuineAudioPath(rawPath: String?, relativePath: String?, displayName: String?): String? {
        if (!rawPath.isNullOrBlank()) {
            return rawPath.trim()
        }
        if (!relativePath.isNullOrBlank() && !displayName.isNullOrBlank()) {
            val rel = relativePath.trim().trim('/')
            val candidate = if (rel.isNotEmpty()) {
                "/storage/emulated/0/$rel/$displayName"
            } else {
                "/storage/emulated/0/$displayName"
            }
            return candidate
        }
        return null
    }

    fun startAutoDetection() {
        if (contentObserver != null) return
        if (!com.example.util.StoragePermissionHelper.hasAudioReadPermission(context) &&
            !com.example.util.StoragePermissionHelper.hasStoragePermission(context)
        ) {
            return
        }

        try {
            contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    scheduleDebouncedAutoDetection()
                }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register MediaStore content observer", e)
        }

        refreshDirectoryObservers()
    }

    fun stopAutoDetection() {
        try {
            contentObserver?.let {
                context.contentResolver.unregisterContentObserver(it)
                contentObserver = null
            }
        } catch (_: Exception) {}

        synchronized(activeFileObservers) {
            activeFileObservers.forEach {
                try { it.stopWatching() } catch (_: Exception) {}
            }
            activeFileObservers.clear()
        }
        debounceJob?.cancel()
    }

    fun refreshDirectoryObservers() {
        scope.launch {
            try {
                synchronized(activeFileObservers) {
                    activeFileObservers.forEach {
                        try { it.stopWatching() } catch (_: Exception) {}
                    }
                    activeFileObservers.clear()
                }

                val includedDirs = folderDao?.getIncludedFoldersList()?.map { it.path } ?: emptyList()
                val targetDirs = mutableListOf<File>()

                if (includedDirs.isNotEmpty()) {
                    includedDirs.forEach { path ->
                        val dir = File(path)
                        if (dir.exists() && dir.isDirectory) targetDirs.add(dir)
                    }
                } else {
                    val publicDirs = listOf(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        File(Environment.getExternalStorageDirectory(), "Music"),
                        File(Environment.getExternalStorageDirectory(), "Download"),
                        File(Environment.getExternalStorageDirectory(), "Downloads"),
                        File(Environment.getExternalStorageDirectory(), "Songs"),
                        File(Environment.getExternalStorageDirectory(), "Audio")
                    )
                    publicDirs.forEach { dir ->
                        if (dir != null && dir.exists() && dir.isDirectory && !targetDirs.contains(dir)) {
                            targetDirs.add(dir)
                        }
                    }
                }

                synchronized(activeFileObservers) {
                    targetDirs.forEach { dir ->
                        try {
                            val observer = createDirectoryObserver(dir.absolutePath)
                            observer.startWatching()
                            activeFileObservers.add(observer)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed refreshing directory observers", e)
            }
        }
    }

    private fun createDirectoryObserver(dirPath: String): FileObserver {
        val mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(File(dirPath), mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileObserverEvent(path)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dirPath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileObserverEvent(path)
                }
            }
        }
    }

    private fun handleFileObserverEvent(path: String?) {
        if (path == null) return
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in AUDIO_EXTENSIONS) {
            scheduleDebouncedAutoDetection()
        }
    }

    private fun scheduleDebouncedAutoDetection() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(1500L)
            val prefs = UserPreferencesRepository.getInstance(context)
            val isOnboarded = prefs.isOnboardingCompleted.firstOrNull() ?: false
            if (isOnboarded) {
                detectAndImportNewSongs()
            }
        }
    }

    suspend fun detectAndImportNewSongs(): Int = withContext(Dispatchers.IO) {
        val prefs = UserPreferencesRepository.getInstance(context)
        val isOnboarded = prefs.isOnboardingCompleted.firstOrNull() ?: false
        if (!isOnboarded) return@withContext 0

        if (!scanMutex.tryLock()) {
            pendingAutoDetect = true
            return@withContext 0
        }

        try {
            val isFullDeviceScan = prefs.fullDeviceScan.firstOrNull() ?: false
            val included = if (isFullDeviceScan) emptyList() else (folderDao?.getIncludedFoldersList() ?: emptyList())
            val excluded = if (isFullDeviceScan) emptyList() else (folderDao?.getExcludedFoldersList() ?: emptyList())
            val ignoreDurationSec = prefs.ignoreAudioDuration.firstOrNull() ?: 0
            val maxDurationSec = prefs.maxSongDuration.firstOrNull() ?: 300
            val ignoreDurationMs = ignoreDurationSec * 1000L
            val maxDurationMs = if (maxDurationSec >= 300) Long.MAX_VALUE else maxDurationSec * 1000L

            val normExcluded = excluded.map { normalizePath(it.path) }
            val normIncluded = included.map { normalizePath(it.path) }

            val existingSongs = songDao.getAllSongsOnce()
            val existingByPath = existingSongs.associateBy { normalizePath(it.path) }
            val existingById = existingSongs.associateBy { it.id }

            var filesDiscovered = 0
            var filesProcessed = 0
            var duplicatesSkipped = 0
            var filteredRejected = 0

            val newlyFoundMap = LinkedHashMap<String, SongEntity>()

            try {
                val projection = buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    add(MediaStore.Audio.Media.ALBUM_ID)
                    add(MediaStore.Audio.Media.ARTIST_ID)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.YEAR)
                    add(MediaStore.Audio.Media.TRACK)
                    add(MediaStore.Audio.Media.DATE_ADDED)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.MIME_TYPE)
                    add(MediaStore.Audio.Media.DATA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Audio.Media.RELATIVE_PATH)
                    }
                }.toTypedArray()

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    val artistIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID)
                    val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                    val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                    val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                    val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                    val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else -1

                    while (cursor.moveToNext()) {
                        filesDiscovered++
                        filesProcessed++

                        val id = if (idCol >= 0) cursor.getLong(idCol) else System.currentTimeMillis()
                        val rawPath = if (dataCol >= 0) {
                            try { cursor.getString(dataCol) } catch (_: Exception) { null }
                        } else null
                        val relPath = if (relCol >= 0) {
                            try { cursor.getString(relCol) } catch (_: Exception) { null }
                        } else null
                        val displayName = if (nameCol >= 0) {
                            try { cursor.getString(nameCol) } catch (_: Exception) { null }
                        } else null

                        val filePath = resolveGenuineAudioPath(rawPath, relPath, displayName)
                        if (filePath.isNullOrBlank()) {
                            filteredRejected++
                            continue
                        }

                        val normP = normalizePath(filePath)
                        if (existingById.containsKey(id) || existingByPath.containsKey(normP) || newlyFoundMap.containsKey(normP)) {
                            duplicatesSkipped++
                            continue
                        }

                        val fileObj = File(filePath)
                        if (filePath.startsWith("/") && (!fileObj.exists() || !fileObj.isFile || fileObj.length() <= 0L)) {
                            filteredRejected++
                            continue
                        }

                        val parentFolder = fileObj.parent?.trimEnd('/') ?: ""
                        val normParent = normalizePath(parentFolder)
                        if (normParent.isNotBlank() && isNormPathExcluded(normParent, normExcluded)) {
                            filteredRejected++
                            continue
                        }

                        if (normIncluded.isNotEmpty() && !isSongInIncludedFolders(normParent, normIncluded)) {
                            filteredRejected++
                            continue
                        }

                        val mimeType = if (mimeCol >= 0) {
                            try { cursor.getString(mimeCol) } catch (_: Exception) { null }
                        } else null

                        val ext = when {
                            !displayName.isNullOrBlank() && displayName.contains('.') -> displayName.substringAfterLast('.').lowercase()
                            filePath.contains('.') -> filePath.substringAfterLast('.').lowercase()
                            else -> ""
                        }

                        val isAudioMime = mimeType?.startsWith("audio/", ignoreCase = true) == true ||
                            mimeType.equals("application/ogg", ignoreCase = true)
                        val isAudioExt = ext in AUDIO_EXTENSIONS || AUDIO_EXTENSIONS.any { filePath.lowercase().endsWith(".$it") }

                        if (!isAudioMime && !isAudioExt) {
                            filteredRejected++
                            continue
                        }

                        val dur = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                        if (dur <= 0L) {
                            if (ignoreDurationMs > 0L) {
                                filteredRejected++
                                continue
                            }
                        } else if ((ignoreDurationMs > 0L && dur < ignoreDurationMs) || (maxDurationMs in 1 until Long.MAX_VALUE && dur > maxDurationMs)) {
                            filteredRejected++
                            continue
                        }

                        val rawTitle = if (titleCol >= 0) cursor.getString(titleCol) else ""
                        val rawArtist = if (artistCol >= 0) cursor.getString(artistCol) else ""
                        val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol) else ""
                        val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
                        val artistId = if (artistIdCol >= 0) cursor.getLong(artistIdCol) else 0L
                        val year = if (yearCol >= 0) cursor.getInt(yearCol) else 0
                        val trackNum = if (trackCol >= 0) cursor.getInt(trackCol) else 0
                        val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else System.currentTimeMillis() / 1000
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()

                        val song = buildOrUpdateSongEntity(
                            existing = null,
                            id = id,
                            rawTitle = rawTitle,
                            rawArtist = rawArtist,
                            rawAlbum = rawAlbum,
                            durationMs = if (dur > 0) dur else 180000L,
                            filePath = filePath,
                            contentUri = contentUri,
                            albumId = albumId,
                            artistId = artistId,
                            trackNum = trackNum,
                            year = year,
                            dateAdded = dateAdded * 1000,
                            format = ext.ifBlank { "MP3" }.uppercase()
                        )
                        newlyFoundMap[normP] = song
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying MediaStore during auto-detect", e)
            }

            // 2. Direct folder scan for newly dropped files
            val dirsToCheck = mutableListOf<File>()
            if (normIncluded.isNotEmpty()) {
                included.forEach { f ->
                    val dir = File(f.path)
                    if (dir.exists() && dir.isDirectory) dirsToCheck.add(dir)
                }
            } else {
                listOf(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    File(Environment.getExternalStorageDirectory(), "Music"),
                    File(Environment.getExternalStorageDirectory(), "Download"),
                    File(Environment.getExternalStorageDirectory(), "Downloads")
                ).forEach { dir ->
                    if (dir != null && dir.exists() && dir.isDirectory && !dirsToCheck.contains(dir)) {
                        dirsToCheck.add(dir)
                    }
                }
            }

            val discoveredInRun = newlyFoundMap.keys.toMutableSet()
            val newFromDirect = mutableListOf<SongEntity>()
            for (dir in dirsToCheck) {
                scanDirectDirectoryForNewSongs(
                    directory = dir,
                    maxDepth = 4,
                    existingByPath = existingByPath,
                    discoveredPaths = discoveredInRun,
                    normIncludedPaths = normIncluded,
                    normExcludedPaths = normExcluded,
                    ignoreAudioDurationMs = ignoreDurationMs,
                    maxAudioDurationMs = maxDurationMs,
                    newSongsList = newFromDirect
                )
            }
            for (s in newFromDirect) {
                val np = normalizePath(s.path)
                if (!newlyFoundMap.containsKey(np) && !existingByPath.containsKey(np)) {
                    newlyFoundMap[np] = s
                }
            }

            val newlyFoundSongs = newlyFoundMap.values.toList()
            if (newlyFoundSongs.isNotEmpty()) {
                insertSongsSafelyInBatches(newlyFoundSongs)
                val totalCount = songDao.getSongCountOnce()
                _scanProgress.value = ScanProgress(
                    status = ScanStatus.COMPLETED,
                    filesDiscovered = filesDiscovered,
                    filesProcessed = filesProcessed,
                    validTracksFound = totalCount,
                    duplicatesSkipped = duplicatesSkipped,
                    filteredRejected = filteredRejected,
                    currentFolder = "",
                    isFinished = true
                )
                try { onScanCompletedListener?.invoke() } catch (_: Exception) {}
                return@withContext newlyFoundSongs.size
            }

            return@withContext 0
        } catch (e: Exception) {
            Log.e(TAG, "Error in auto-detect new songs", e)
            return@withContext 0
        } finally {
            scanMutex.unlock()
            if (pendingAutoDetect) {
                pendingAutoDetect = false
                scheduleDebouncedAutoDetection()
            }
        }
    }

    /**
     * Authoritative Full Rescan across MediaStore and target directories.
     * Uses batch deletes (`deleteSongsSafelyInBatches`) and batch inserts (`insertSongsSafelyInBatches`)
     * so scanning never causes SQLite / Room InvalidationTracker storms or OOM crashes.
     */
    suspend fun scanLibrary(
        includedFolders: List<FolderEntity> = emptyList(),
        excludedFolders: List<FolderEntity> = emptyList(),
        ignoreAudioDurationMs: Long = 0L,
        maxAudioDurationMs: Long = Long.MAX_VALUE
    ) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            var filesDiscovered = 0
            var filesProcessed = 0
            var duplicatesSkipped = 0
            var filteredRejected = 0

            _scanProgress.value = ScanProgress(
                status = ScanStatus.SCANNING,
                filesDiscovered = 0,
                filesProcessed = 0,
                validTracksFound = 0,
                duplicatesSkipped = 0,
                filteredRejected = 0,
                currentFolder = "Searching media index...",
                isScanning = true,
                isFinished = false
            )

            val normExcludedPaths = excludedFolders.map { normalizePath(it.path) }.filter { it.isNotBlank() }
            val normIncludedPaths = includedFolders.map { normalizePath(it.path) }.filter { it.isNotBlank() }

            val foundSongsByNormPath = LinkedHashMap<String, SongEntity>()
            val seenMediaStoreIds = HashSet<Long>()
            var lastEmitTime = 0L

            try {
                val projectionList = buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    add(MediaStore.Audio.Media.ALBUM_ID)
                    add(MediaStore.Audio.Media.ARTIST_ID)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.YEAR)
                    add(MediaStore.Audio.Media.TRACK)
                    add(MediaStore.Audio.Media.DATE_ADDED)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.MIME_TYPE)
                    add(MediaStore.Audio.Media.DATA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Audio.Media.RELATIVE_PATH)
                    }
                }.toTypedArray()

                var cursor: android.database.Cursor? = null
                try {
                    cursor = context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projectionList,
                        null,
                        null,
                        "${MediaStore.Audio.Media.TITLE} ASC"
                    )
                } catch (_: Exception) {}

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
                    val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else -1

                    while (c.moveToNext()) {
                        filesDiscovered++
                        filesProcessed++

                        val id = if (idCol >= 0) c.getLong(idCol) else 0L
                        if (id <= 0L) {
                            filteredRejected++
                            continue
                        }
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val displayName = if (nameCol >= 0) {
                            try { c.getString(nameCol) } catch (_: Exception) { null }
                        } else null
                        val rawPath = if (dataCol >= 0) {
                            try { c.getString(dataCol) } catch (_: Exception) { null }
                        } else null
                        val relPath = if (relCol >= 0) {
                            try { c.getString(relCol) } catch (_: Exception) { null }
                        } else null

                        val filePath = resolveGenuineAudioPath(rawPath, relPath, displayName)
                        if (filePath.isNullOrBlank()) {
                            filteredRejected++
                            continue
                        }

                        val fileObj = File(filePath)
                        if (filePath.startsWith("/") && (!fileObj.exists() || !fileObj.isFile || fileObj.length() <= 0L)) {
                            filteredRejected++
                            continue
                        }

                        val parentFolder = fileObj.parent?.trimEnd('/') ?: ""
                        val normParent = normalizePath(parentFolder)

                        if (normParent.isNotBlank() && isNormPathExcluded(normParent, normExcludedPaths)) {
                            filteredRejected++
                            continue
                        }

                        if (normIncludedPaths.isNotEmpty() && !isSongInIncludedFolders(normParent, normIncludedPaths)) {
                            filteredRejected++
                            continue
                        }

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

                        if (!isAudioMime && !isAudioExt) {
                            filteredRejected++
                            continue
                        }

                        val duration = if (durationCol >= 0) c.getLong(durationCol) else 0L
                        if (duration <= 0L) {
                            if (ignoreAudioDurationMs > 0L) {
                                filteredRejected++
                                continue
                            }
                        } else if ((ignoreAudioDurationMs > 0L && duration < ignoreAudioDurationMs) ||
                            (maxAudioDurationMs in 1 until Long.MAX_VALUE && duration > maxAudioDurationMs)
                        ) {
                            filteredRejected++
                            continue
                        }

                        val normPathKey = normalizePath(filePath)
                        if (foundSongsByNormPath.containsKey(normPathKey) || !seenMediaStoreIds.add(id)) {
                            duplicatesSkipped++
                            continue
                        }

                        val rawTitle = if (titleCol >= 0) c.getString(titleCol) else null
                        val rawArtist = if (artistCol >= 0) c.getString(artistCol) else null
                        val rawAlbum = if (albumCol >= 0) c.getString(albumCol) else null
                        val albumId = if (albumIdCol >= 0) c.getLong(albumIdCol) else 0L
                        val artistId = if (artistIdCol >= 0) c.getLong(artistIdCol) else 0L
                        val year = if (yearCol >= 0) c.getInt(yearCol) else 0
                        val track = if (trackCol >= 0) c.getInt(trackCol) else 0
                        val dateAdded = if (dateAddedCol >= 0) c.getLong(dateAddedCol) else System.currentTimeMillis() / 1000

                        val filenameForTag = displayName ?: fileObj.name
                        val cleanedTitle = TagCleaner.cleanTitle(rawTitle, filenameForTag)
                        val cleanedArtist = TagCleaner.cleanArtist(rawArtist)
                        val cleanedAlbum = TagCleaner.cleanAlbum(rawAlbum)
                        val format = ext.ifBlank { "MP3" }.uppercase()

                        foundSongsByNormPath[normPathKey] = SongEntity(
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

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime > PROGRESS_EMIT_INTERVAL_MS) {
                            lastEmitTime = now
                            val folderName = if (parentFolder.isNotBlank()) parentFolder.substringAfterLast('/').ifBlank { "Media Store" } else "Media Store"
                            _scanProgress.value = ScanProgress(
                                status = ScanStatus.SCANNING,
                                filesDiscovered = filesDiscovered,
                                filesProcessed = filesProcessed,
                                validTracksFound = foundSongsByNormPath.size,
                                duplicatesSkipped = duplicatesSkipped,
                                filteredRejected = filteredRejected,
                                currentFolder = folderName,
                                isScanning = true,
                                isFinished = false
                            )
                        }
                    }
                }

                // 2. Direct Storage Traversal ONLY for files not already indexed by MediaStore
                val retriever = MediaMetadataRetriever()
                try {
                    val rootsToScan = mutableListOf<File>()
                    val seenCanonicalRoots = HashSet<String>()

                    if (normIncludedPaths.isNotEmpty()) {
                        includedFolders.forEach { folder ->
                            val dir = File(folder.path)
                            if (dir.exists() && dir.isDirectory && seenCanonicalRoots.add(normalizePath(dir.absolutePath))) {
                                rootsToScan.add(dir)
                            }
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
                            if (dir != null && dir.exists() && dir.isDirectory && seenCanonicalRoots.add(normalizePath(dir.absolutePath))) {
                                rootsToScan.add(dir)
                            }
                        }
                    }

                    for (root in rootsToScan) {
                        scanDirectoryRecursivelyFast(
                            directory = root,
                            normIncludedPaths = normIncludedPaths,
                            normExcludedPaths = normExcludedPaths,
                            ignoreAudioDurationMs = ignoreAudioDurationMs,
                            maxAudioDurationMs = maxAudioDurationMs,
                            foundSongsByNormPath = foundSongsByNormPath,
                            retriever = retriever,
                            onProgress = { currentDir, _ ->
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > PROGRESS_EMIT_INTERVAL_MS) {
                                    lastEmitTime = now
                                    _scanProgress.value = ScanProgress(
                                        status = ScanStatus.SCANNING,
                                        filesDiscovered = filesDiscovered.coerceAtLeast(foundSongsByNormPath.size),
                                        filesProcessed = filesProcessed.coerceAtLeast(foundSongsByNormPath.size),
                                        validTracksFound = foundSongsByNormPath.size,
                                        duplicatesSkipped = duplicatesSkipped,
                                        filteredRejected = filteredRejected,
                                        currentFolder = currentDir,
                                        isScanning = true,
                                        isFinished = false
                                    )
                                }
                            }
                        )
                    }
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }

                // 3. Synchronize Discovered Songs with Room Database
                val existingSongs = songDao.getAllSongsOnce()
                val existingById = existingSongs.associateBy { it.id }
                val existingByPath = existingSongs.associateBy { normalizePath(it.path) }

                val rawSongsList = foundSongsByNormPath.values.toList()
                val mergedSongsList = rawSongsList.map { song ->
                    val normP = normalizePath(song.path)
                    val existing = existingByPath[normP] ?: existingById[song.id]
                    buildOrUpdateSongEntity(
                        existing = existing,
                        id = existing?.id ?: song.id,
                        rawTitle = song.title,
                        rawArtist = song.artist,
                        rawAlbum = song.album,
                        durationMs = song.durationMs,
                        filePath = song.path,
                        contentUri = song.contentUriString,
                        albumId = song.albumId,
                        artistId = song.artistId,
                        trackNum = song.trackNumber,
                        year = song.year,
                        dateAdded = song.dateAdded,
                        format = song.format
                    )
                }.distinctBy { it.id }

                val validNormPathsInThisScan = mergedSongsList.map { normalizePath(it.path) }.toHashSet()
                val validIdsInThisScan = mergedSongsList.map { it.id }.toHashSet()

                songDao.deleteUnusedSampleSongs()

                val obsoleteIds = existingSongs.mapNotNull { dbSong ->
                    val normDbPath = normalizePath(dbSong.path)
                    if (!validNormPathsInThisScan.contains(normDbPath) || !validIdsInThisScan.contains(dbSong.id)) {
                        dbSong.id
                    } else null
                }
                deleteSongsSafelyInBatches(obsoleteIds)

                if (mergedSongsList.isNotEmpty()) {
                    insertSongsSafelyInBatches(mergedSongsList)
                }

                val finalCount = mergedSongsList.size
                _scanProgress.value = ScanProgress(
                    status = ScanStatus.COMPLETED,
                    filesDiscovered = filesDiscovered,
                    filesProcessed = filesProcessed,
                    validTracksFound = finalCount,
                    duplicatesSkipped = duplicatesSkipped,
                    filteredRejected = filteredRejected,
                    currentFolder = "",
                    isScanning = false,
                    isFinished = true
                )
                try { onScanCompletedListener?.invoke() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Error in scanLibrary", e)
                _scanProgress.value = ScanProgress(
                    status = ScanStatus.ERROR,
                    errorMessage = e.localizedMessage ?: "Scan encountered an error",
                    validTracksFound = songDao.getSongCountOnce(),
                    isScanning = false,
                    isFinished = false
                )
            }
        }
    }

    /**
     * Fast Quick Scan: Quickly prunes missing/excluded/filtered files and indexes new audio tracks
     * using batch updates and batch deletes to prevent Room InvalidationTracker storms.
     */
    suspend fun quickScanAndCleanLibrary(
        includedFolders: List<FolderEntity> = emptyList(),
        excludedFolders: List<FolderEntity> = emptyList(),
        ignoreAudioDurationMs: Long = 0L,
        maxAudioDurationMs: Long = Long.MAX_VALUE
    ) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            var filesDiscovered = 0
            var filesProcessed = 0
            var duplicatesSkipped = 0
            var filteredRejected = 0

            val normExcludedPaths = excludedFolders.map { normalizePath(it.path) }.filter { it.isNotBlank() }
            val normIncludedPaths = includedFolders.map { normalizePath(it.path) }.filter { it.isNotBlank() }
            var lastEmitTime = 0L

            try {
                val existingSongs = songDao.getAllSongsOnce()
                val seenNormPaths = HashSet<String>()
                val songsToUpdate = mutableListOf<SongEntity>()
                val idsToDelete = mutableListOf<Long>()

                // Sort so MediaStore IDs (< 1_000_000_000_000L) win over synthetic hash IDs if duplicates exist
                val sortedExisting = existingSongs.sortedBy { if (it.id in 1 until 1_000_000_000_000L) 0 else 1 }

                for (dbSong in sortedExisting) {
                    filesDiscovered++
                    filesProcessed++

                    val normDbPath = normalizePath(dbSong.path)
                    if (!seenNormPaths.add(normDbPath)) {
                        idsToDelete.add(dbSong.id)
                        duplicatesSkipped++
                        continue
                    }

                    if (!dbSong.path.startsWith("sample_") && !dbSong.contentUriString.startsWith("http")) {
                        val fileObj = File(dbSong.path)
                        val parentFolder = fileObj.parent?.trimEnd('/') ?: ""
                        val normParent = normalizePath(parentFolder)
                        val isExcluded = isNormPathExcluded(normParent, normExcludedPaths)
                        val isNotIncluded = !isSongInIncludedFolders(normParent, normIncludedPaths)
                        val isAccessible = try { fileObj.exists() && fileObj.isFile && fileObj.length() > 0L } catch (_: Exception) { true }
                        val isTooShort = ignoreAudioDurationMs > 0 && dbSong.durationMs > 0 && dbSong.durationMs < ignoreAudioDurationMs
                        val isTooLong = maxAudioDurationMs in 1 until Long.MAX_VALUE && dbSong.durationMs > 0 && dbSong.durationMs > maxAudioDurationMs

                        if (isExcluded || isNotIncluded || !isAccessible || isTooShort || isTooLong) {
                            idsToDelete.add(dbSong.id)
                            filteredRejected++
                            continue
                        }
                    }

                    val isManual = dbSong.isManuallyEdited || com.example.util.ManualTagStore.isManuallyEdited(context, dbSong.id, dbSong.path)
                    if (!isManual) {
                        val filename = File(dbSong.path).name
                        val normalizedTitle = TagCleaner.cleanTitle(dbSong.title, filename)
                        val normalizedArtist = TagCleaner.cleanArtist(dbSong.artist)
                        val normalizedAlbum = TagCleaner.cleanAlbum(dbSong.album)

                        if (normalizedTitle != dbSong.title || normalizedArtist != dbSong.artist || normalizedAlbum != dbSong.album) {
                            songsToUpdate.add(
                                dbSong.copy(
                                    title = normalizedTitle,
                                    artist = normalizedArtist,
                                    album = normalizedAlbum
                                )
                            )
                        }
                    }
                }

                deleteSongsSafelyInBatches(idsToDelete)
                updateSongsSafelyInBatches(songsToUpdate)
                songDao.deleteMissingSongs()

                val refreshedExisting = songDao.getAllSongsOnce()
                val existingByPath = refreshedExisting.associateBy { normalizePath(it.path) }
                val existingById = refreshedExisting.associateBy { it.id }

                _scanProgress.value = ScanProgress(
                    status = ScanStatus.SCANNING,
                    filesDiscovered = filesDiscovered,
                    filesProcessed = filesProcessed,
                    validTracksFound = refreshedExisting.size,
                    duplicatesSkipped = duplicatesSkipped,
                    filteredRejected = filteredRejected,
                    currentFolder = "Quick indexing...",
                    isScanning = true,
                    isFinished = false
                )

                // 2. Scan MediaStore for newly added tracks
                val newSongsByNormPath = LinkedHashMap<String, SongEntity>()
                val projectionList = buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    add(MediaStore.Audio.Media.ALBUM_ID)
                    add(MediaStore.Audio.Media.ARTIST_ID)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.YEAR)
                    add(MediaStore.Audio.Media.TRACK)
                    add(MediaStore.Audio.Media.DATE_ADDED)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.MIME_TYPE)
                    add(MediaStore.Audio.Media.DATA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Audio.Media.RELATIVE_PATH)
                    }
                }.toTypedArray()

                var cursor: android.database.Cursor? = null
                try {
                    cursor = context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projectionList,
                        null,
                        null,
                        "${MediaStore.Audio.Media.TITLE} ASC"
                    )
                } catch (_: Exception) {}

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
                    val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else -1

                    while (c.moveToNext()) {
                        filesDiscovered++
                        filesProcessed++

                        val mediaStoreId = if (idCol >= 0) c.getLong(idCol) else 0L
                        if (mediaStoreId <= 0L) continue
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
                        val displayName = if (nameCol >= 0) {
                            try { c.getString(nameCol) } catch (_: Exception) { null }
                        } else null
                        val rawPath = if (dataCol >= 0) {
                            try { c.getString(dataCol) } catch (_: Exception) { null }
                        } else null
                        val relPath = if (relCol >= 0) {
                            try { c.getString(relCol) } catch (_: Exception) { null }
                        } else null

                        val filePath = resolveGenuineAudioPath(rawPath, relPath, displayName)
                        if (filePath.isNullOrBlank()) {
                            filteredRejected++
                            continue
                        }
                        val normP = normalizePath(filePath)

                        if (existingByPath.containsKey(normP) || existingById.containsKey(mediaStoreId) || newSongsByNormPath.containsKey(normP)) {
                            duplicatesSkipped++
                            continue
                        }

                        val fileObj = File(filePath)
                        if (filePath.startsWith("/") && (!fileObj.exists() || !fileObj.isFile || fileObj.length() <= 0L)) {
                            filteredRejected++
                            continue
                        }

                        val parentFolder = fileObj.parent?.trimEnd('/') ?: ""
                        val normParent = normalizePath(parentFolder)
                        if (normParent.isNotBlank() && isNormPathExcluded(normParent, normExcludedPaths)) {
                            filteredRejected++
                            continue
                        }

                        if (normIncludedPaths.isNotEmpty() && !isSongInIncludedFolders(normParent, normIncludedPaths)) {
                            filteredRejected++
                            continue
                        }

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

                        if (!isAudioMime && !isAudioExt) {
                            filteredRejected++
                            continue
                        }

                        val dur = if (durationCol >= 0) c.getLong(durationCol) else 0L
                        if (dur <= 0L) {
                            if (ignoreAudioDurationMs > 0L) {
                                filteredRejected++
                                continue
                            }
                        } else if ((ignoreAudioDurationMs > 0 && dur < ignoreAudioDurationMs) ||
                            (maxAudioDurationMs in 1 until Long.MAX_VALUE && dur > maxAudioDurationMs)
                        ) {
                            filteredRejected++
                            continue
                        }

                        val rawTitle = if (titleCol >= 0) c.getString(titleCol) else ""
                        val rawArtist = if (artistCol >= 0) c.getString(artistCol) else ""
                        val rawAlbum = if (albumCol >= 0) c.getString(albumCol) else ""
                        val albumId = if (albumIdCol >= 0) c.getLong(albumIdCol) else 0L
                        val artistId = if (artistIdCol >= 0) c.getLong(artistIdCol) else 0L
                        val year = if (yearCol >= 0) c.getInt(yearCol) else 0
                        val trackNum = if (trackCol >= 0) c.getInt(trackCol) else 0
                        val dateAdded = if (dateAddedCol >= 0) c.getLong(dateAddedCol) else System.currentTimeMillis() / 1000
                        val format = ext.ifBlank { "MP3" }.uppercase()

                        val newSong = buildOrUpdateSongEntity(
                            existing = null,
                            id = mediaStoreId,
                            rawTitle = rawTitle,
                            rawArtist = rawArtist,
                            rawAlbum = rawAlbum,
                            durationMs = if (dur > 0) dur else 180000L,
                            filePath = filePath,
                            contentUri = uri.toString(),
                            albumId = albumId,
                            artistId = artistId,
                            trackNum = trackNum,
                            year = year,
                            dateAdded = dateAdded * 1000,
                            format = format
                        )
                        newSongsByNormPath[normP] = newSong

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime > PROGRESS_EMIT_INTERVAL_MS) {
                            lastEmitTime = now
                            val folderName = if (parentFolder.isNotBlank()) parentFolder.substringAfterLast('/').ifBlank { "Quick Scan" } else "Quick Scan"
                            _scanProgress.value = ScanProgress(
                                status = ScanStatus.SCANNING,
                                filesDiscovered = filesDiscovered,
                                filesProcessed = filesProcessed,
                                validTracksFound = refreshedExisting.size + newSongsByNormPath.size,
                                duplicatesSkipped = duplicatesSkipped,
                                filteredRejected = filteredRejected,
                                currentFolder = folderName,
                                isScanning = true,
                                isFinished = false
                            )
                        }
                    }
                }

                // 3. Fast direct check on target storage directories
                try {
                    val fastScanDirs = mutableListOf<File>()
                    val seenDirs = HashSet<String>()
                    if (normIncludedPaths.isNotEmpty()) {
                        includedFolders.forEach { f ->
                            val dir = File(f.path)
                            if (dir.exists() && dir.isDirectory && seenDirs.add(normalizePath(dir.absolutePath))) {
                                fastScanDirs.add(dir)
                            }
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
                            if (dir != null && dir.exists() && dir.isDirectory && seenDirs.add(normalizePath(dir.absolutePath))) {
                                fastScanDirs.add(dir)
                            }
                        }
                    }

                    val discoveredPaths = newSongsByNormPath.keys.toMutableSet()
                    val directNewList = mutableListOf<SongEntity>()
                    for (dir in fastScanDirs) {
                        scanDirectDirectoryForNewSongs(
                            directory = dir,
                            maxDepth = 3,
                            existingByPath = existingByPath,
                            discoveredPaths = discoveredPaths,
                            normIncludedPaths = normIncludedPaths,
                            normExcludedPaths = normExcludedPaths,
                            ignoreAudioDurationMs = ignoreAudioDurationMs,
                            maxAudioDurationMs = maxAudioDurationMs,
                            newSongsList = directNewList,
                            onProgress = { currentDir, _ ->
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > PROGRESS_EMIT_INTERVAL_MS) {
                                    lastEmitTime = now
                                    val currentValid = refreshedExisting.size + newSongsByNormPath.size + directNewList.size
                                    _scanProgress.value = ScanProgress(
                                        status = ScanStatus.SCANNING,
                                        filesDiscovered = filesDiscovered.coerceAtLeast(currentValid),
                                        filesProcessed = filesProcessed.coerceAtLeast(currentValid),
                                        validTracksFound = currentValid,
                                        duplicatesSkipped = duplicatesSkipped,
                                        filteredRejected = filteredRejected,
                                        currentFolder = currentDir,
                                        isScanning = true,
                                        isFinished = false
                                    )
                                }
                            }
                        )
                    }
                    for (s in directNewList) {
                        val np = normalizePath(s.path)
                        if (!newSongsByNormPath.containsKey(np) && !existingByPath.containsKey(np)) {
                            newSongsByNormPath[np] = s
                        }
                    }
                } catch (_: Exception) {}

                val finalNewSongs = newSongsByNormPath.values.distinctBy { it.id }.toList()
                if (finalNewSongs.isNotEmpty()) {
                    insertSongsSafelyInBatches(finalNewSongs)
                }

                val totalCount = songDao.getSongCountOnce()
                _scanProgress.value = ScanProgress(
                    status = ScanStatus.COMPLETED,
                    filesDiscovered = filesDiscovered,
                    filesProcessed = filesProcessed,
                    validTracksFound = totalCount,
                    duplicatesSkipped = duplicatesSkipped,
                    filteredRejected = filteredRejected,
                    currentFolder = "",
                    isScanning = false,
                    isFinished = true
                )
                try { onScanCompletedListener?.invoke() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Error in quickScanAndCleanLibrary", e)
                _scanProgress.value = ScanProgress(
                    status = ScanStatus.ERROR,
                    errorMessage = e.localizedMessage ?: "Scan encountered an error",
                    validTracksFound = songDao.getSongCountOnce(),
                    isScanning = false,
                    isFinished = false
                )
            }
        }
    }

    private suspend fun scanDirectoryRecursivelyFast(
        directory: File,
        normIncludedPaths: List<String>,
        normExcludedPaths: List<String>,
        ignoreAudioDurationMs: Long,
        maxAudioDurationMs: Long,
        foundSongsByNormPath: MutableMap<String, SongEntity>,
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
                scanDirectoryRecursivelyFast(
                    directory = file,
                    normIncludedPaths = normIncludedPaths,
                    normExcludedPaths = normExcludedPaths,
                    ignoreAudioDurationMs = ignoreAudioDurationMs,
                    maxAudioDurationMs = maxAudioDurationMs,
                    foundSongsByNormPath = foundSongsByNormPath,
                    retriever = retriever,
                    onProgress = onProgress
                )
            } else if (file.isFile && file.length() > 0L) {
                val ext = file.extension.lowercase()
                if (ext in AUDIO_EXTENSIONS) {
                    val filePath = file.absolutePath
                    val normPath = normalizePath(filePath)
                    if (!foundSongsByNormPath.containsKey(normPath)) {
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

                        if (duration <= 0L) {
                            if (ignoreAudioDurationMs > 0L) continue
                        } else if ((ignoreAudioDurationMs > 0L && duration < ignoreAudioDurationMs) ||
                            (maxAudioDurationMs in 1 until Long.MAX_VALUE && duration > maxAudioDurationMs)
                        ) {
                            continue
                        }

                        val cleanedTitle = TagCleaner.cleanTitle(rawTitle, file.name)
                        val cleanedArtist = TagCleaner.cleanArtist(rawArtist)
                        val cleanedAlbum = TagCleaner.cleanAlbum(rawAlbum)
                        val fileId = computeStablePathId(normPath)

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
                        foundSongsByNormPath[normPath] = song
                        onProgress?.invoke(directory.name, foundSongsByNormPath.size)
                        try {
                            MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun scanDirectDirectoryForNewSongs(
        directory: File,
        maxDepth: Int,
        existingByPath: Map<String, SongEntity>,
        discoveredPaths: MutableSet<String>,
        normIncludedPaths: List<String>,
        normExcludedPaths: List<String>,
        ignoreAudioDurationMs: Long,
        maxAudioDurationMs: Long = Long.MAX_VALUE,
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
                    maxAudioDurationMs = maxAudioDurationMs,
                    newSongsList = newSongsList,
                    onProgress = onProgress
                )
            } else if (file.isFile && file.length() > 0L) {
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

                        if (duration <= 0L) {
                            if (ignoreAudioDurationMs > 0L) continue
                        } else if ((ignoreAudioDurationMs > 0L && duration < ignoreAudioDurationMs) ||
                            (maxAudioDurationMs in 1 until Long.MAX_VALUE && duration > maxAudioDurationMs)
                        ) {
                            continue
                        }

                        val fileId = computeStablePathId(normPath)
                        val song = buildOrUpdateSongEntity(
                            existing = null,
                            id = fileId,
                            rawTitle = rawTitle,
                            rawArtist = rawArtist,
                            rawAlbum = rawAlbum,
                            durationMs = if (duration > 0) duration else 180000L,
                            filePath = filePath,
                            contentUri = Uri.fromFile(file).toString(),
                            dateAdded = file.lastModified(),
                            format = ext.uppercase()
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

    private fun normalizePath(rawPath: String): String {
        var p = rawPath.trim().replace('\\', '/')
        while (p.contains("//")) {
            p = p.replace("//", "/")
        }
        p = p.trimEnd('/')
        if (p.equals("/sdcard", ignoreCase = true) || p.startsWith("/sdcard/", ignoreCase = true)) {
            p = "/storage/emulated/0" + p.substring(7)
        } else if (p.equals("/storage/self/primary", ignoreCase = true) || p.startsWith("/storage/self/primary/", ignoreCase = true)) {
            p = "/storage/emulated/0" + p.substring(21)
        } else if (p.equals("/mnt/sdcard", ignoreCase = true) || p.startsWith("/mnt/sdcard/", ignoreCase = true)) {
            p = "/storage/emulated/0" + p.substring(11)
        }
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

    private fun isDirInIncludedFolders(normDir: String, normIncludedPaths: List<String>): Boolean {
        if (normIncludedPaths.isEmpty()) return true
        return normIncludedPaths.any { normIncluded ->
            normDir == normIncluded || normDir.startsWith("$normIncluded/") || normIncluded.startsWith("$normDir/")
        }
    }
}