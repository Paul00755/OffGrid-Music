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

        @Volatile
        private var INSTANCE: LibraryScanner? = null

        fun getInstance(context: Context, songDao: SongDao, folderDao: FolderDao? = null): LibraryScanner {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryScanner(context.applicationContext, songDao, folderDao).also {
                    INSTANCE = it
                }
            }
        }
    }

    /**
     * Authoritative single pipeline for creating or updating SongEntity instances.
     * Guarantees:
     * - User-manually-edited metadata (title, artist, album, artwork) is preserved 100%.
     * - Unedited titles/metadata are cleaned using the authoritative TagCleaner.
     * - Stable identity and playback stats (playCount, lastPlayed, isFavorite) are retained.
     * - Existing artwork is never lost during re-indexing.
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
        val manualMeta = com.example.util.ManualTagStore.getManualMetadata(context, existing?.id ?: id, filePath)
        val isManual = existing?.isManuallyEdited == true ||
                (existing != null && com.example.util.ManualTagStore.isManuallyEdited(context, existing.id, existing.path)) ||
                manualMeta != null

        val manualArt = manualMeta?.artworkUri ?: com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, existing?.id ?: id, filePath)
        val finalArtwork = manualArt ?: existing?.artworkUri ?: try {
            com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, existing?.id ?: id, filePath)
        } catch (_: Exception) { null }

        if (isManual) {
            // CRITICAL: Preserve manual user-edited tags and metadata 100%
            val resolvedTitle = manualMeta?.title?.takeIf { it.isNotBlank() } ?: (if (existing?.isManuallyEdited == true) existing.title else rawTitle) ?: filename.substringBeforeLast(".")
            val resolvedArtist = manualMeta?.artist?.takeIf { it.isNotBlank() } ?: (if (existing?.isManuallyEdited == true) existing.artist else rawArtist) ?: "Unknown Artist"
            val resolvedAlbum = manualMeta?.album?.takeIf { it.isNotBlank() } ?: (if (existing?.isManuallyEdited == true) existing.album else rawAlbum) ?: "Unknown Album"
            val resolvedGenre = manualMeta?.genre ?: existing?.genre ?: "Music"
            val resolvedYear = manualMeta?.year ?: (if (year > 0) year else (existing?.year ?: 0))
            val resolvedTrack = manualMeta?.trackNumber ?: (if (trackNum > 0) trackNum else (existing?.trackNumber ?: 0))

            return SongEntity(
                id = existing?.id ?: id,
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

        // Clean using authoritative TagCleaner
        val cleanTitle = TagCleaner.cleanTitle(rawTitle, filename)
        val cleanArtist = TagCleaner.cleanArtist(rawArtist)
        val cleanAlbum = TagCleaner.cleanAlbum(rawAlbum)

        return SongEntity(
            id = existing?.id ?: id,
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

    init {
        // Automatically start real-time observation for newly added songs
        startAutoDetection()
    }

    /**
     * Starts listening for newly added audio files across accessible storage partitions
     * and custom included directories using MediaStore ContentObserver and FileObserver.
     */
    fun startAutoDetection() {
        if (contentObserver != null) return

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
            Log.d(TAG, "MediaStore ContentObserver registered successfully")
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

    /**
     * Refreshes the FileObserver watch list to monitor all configured included directories
     * or standard music folders if no specific folders are chosen.
     */
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
                Log.d(TAG, "Directory observers active on ${targetDirs.size} locations")
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
            // Debounce filesystem and MediaStore events to coalesce rapid batch arrivals
            delay(1200L)
            detectAndImportNewSongs()
        }
    }

    /**
     * Incremental automatic detection of newly added songs.
     * Checks MediaStore and monitored directories for newly available tracks,
     * validates filter criteria, skips duplicates, and immediately updates Room.
     */
    suspend fun detectAndImportNewSongs(): Int = withContext(Dispatchers.IO) {
        if (!scanMutex.tryLock()) {
            pendingAutoDetect = true
            return@withContext 0
        }

        try {
            val included = folderDao?.getIncludedFoldersList() ?: emptyList()
            val excluded = folderDao?.getExcludedFoldersList() ?: emptyList()
            val prefs = UserPreferencesRepository(context)
            val ignoreDurationSec = prefs.ignoreAudioDuration.firstOrNull() ?: 0
            val ignoreDurationMs = ignoreDurationSec * 1000L

            val normExcluded = excluded.map { normalizePath(it.path) }
            val normIncluded = included.map { normalizePath(it.path) }

            val existingSongs = songDao.getAllSongsOnce()
            val existingByPath = existingSongs.associateBy { normalizePath(it.path) }
            val existingById = existingSongs.associateBy { it.id }

            var filesDiscovered = 0
            var filesProcessed = 0
            var duplicatesSkipped = 0
            var filteredRejected = 0

            val newlyFoundSongs = mutableListOf<SongEntity>()

            // 1. Query MediaStore for audio entries
            try {
                val projection = arrayOf(
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

                    while (cursor.moveToNext()) {
                        filesDiscovered++
                        filesProcessed++

                        val id = if (idCol >= 0) cursor.getLong(idCol) else System.currentTimeMillis()
                        val rawPath = if (dataCol >= 0) {
                            try { cursor.getString(dataCol) } catch (_: Exception) { null }
                        } else null

                        val displayName = if (nameCol >= 0) {
                            try { cursor.getString(nameCol) } catch (_: Exception) { null }
                        } else null

                        val filePath = rawPath ?: "/storage/emulated/0/Music/${displayName ?: "audio_$id.mp3"}"
                        val normP = normalizePath(filePath)

                        if (existingById.containsKey(id) || existingByPath.containsKey(normP)) {
                            duplicatesSkipped++
                            continue
                        }

                        val parentFolder = if (filePath.startsWith("/")) File(filePath).parent?.trimEnd('/') ?: "" else ""
                        if (parentFolder.isNotBlank() && isNormPathExcluded(normalizePath(parentFolder), normExcluded)) {
                            filteredRejected++
                            continue
                        }

                        if (normIncluded.isNotEmpty()) {
                            val normParent = normalizePath(parentFolder)
                            if (!isSongInIncludedFolders(normParent, normIncluded)) {
                                filteredRejected++
                                continue
                            }
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

                        if (!isAudioMime && !isAudioExt && ext.isNotBlank() && !ext.equals("mp3", ignoreCase = true)) {
                            filteredRejected++
                            continue
                        }

                        val dur = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                        if (dur > 0 && dur < ignoreDurationMs) {
                            filteredRejected++
                            continue
                        }

                        val rawTitle = if (titleCol >= 0) cursor.getString(titleCol) else ""
                        val rawArtist = if (artistCol >= 0) cursor.getString(artistCol) else ""
                        val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol) else ""
                        val filename = displayName ?: File(filePath).name

                        val cleanTitle = TagCleaner.cleanTitle(rawTitle, filename)
                        val cleanArtist = TagCleaner.cleanArtist(rawArtist)
                        val cleanAlbum = TagCleaner.cleanAlbum(rawAlbum)
                        val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
                        val artistId = if (artistIdCol >= 0) cursor.getLong(artistIdCol) else 0L
                        val year = if (yearCol >= 0) cursor.getInt(yearCol) else 0
                        val trackNum = if (trackCol >= 0) cursor.getInt(trackCol) else 0
                        val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else System.currentTimeMillis() / 1000

                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val manualArt = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, id, filePath)
                        val artworkUri: String? = manualArt ?: try {
                            com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, id, filePath)
                        } catch (_: Exception) { null }

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
                        newlyFoundSongs.add(song)
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

            val discoveredInRun = newlyFoundSongs.map { normalizePath(it.path) }.toMutableSet()
            for (dir in dirsToCheck) {
                scanDirectDirectoryForNewSongs(
                    directory = dir,
                    maxDepth = 3,
                    existingByPath = existingByPath,
                    discoveredPaths = discoveredInRun,
                    normIncludedPaths = normIncluded,
                    normExcludedPaths = normExcluded,
                    ignoreAudioDurationMs = ignoreDurationMs,
                    newSongsList = newlyFoundSongs
                )
            }

            if (newlyFoundSongs.isNotEmpty()) {
                songDao.insertSongs(newlyFoundSongs)
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
                Log.i(TAG, "Auto-detected and imported ${newlyFoundSongs.size} new songs. Total: $totalCount")
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
     * Full rescan across all device audio sources and custom directories.
     * Reports actual files discovered, processed, and valid tracks found without fabricating totals.
     */
    suspend fun scanLibrary(
        includedFolders: List<FolderEntity> = emptyList(),
        excludedFolders: List<FolderEntity> = emptyList(),
        ignoreAudioDurationMs: Long = 0L,
        maxAudioDurationMs: Long = Long.MAX_VALUE
    ) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val initialCount = songDao.getSongCountOnce()
            var filesDiscovered = 0
            var filesProcessed = 0
            var duplicatesSkipped = 0
            var filteredRejected = 0

            _scanProgress.value = ScanProgress(
                status = ScanStatus.SCANNING,
                filesDiscovered = 0,
                filesProcessed = 0,
                validTracksFound = initialCount,
                duplicatesSkipped = 0,
                filteredRejected = 0,
                currentFolder = "Searching media index...",
                isScanning = true,
                isFinished = false
            )

            val normExcludedPaths = excludedFolders.map { normalizePath(it.path) }
            val normIncludedPaths = includedFolders.map { normalizePath(it.path) }
            val foundSongsMap = mutableMapOf<String, SongEntity>()
            var lastEmitTime = 0L

            try {
                // 1. Resilient MediaStore Scan
                val projectionList = arrayOf(
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

                    while (c.moveToNext()) {
                        filesDiscovered++
                        filesProcessed++

                        val id = if (idCol >= 0) c.getLong(idCol) else System.currentTimeMillis()
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val displayName = if (nameCol >= 0) {
                            try { c.getString(nameCol) } catch (_: Exception) { null }
                        } else null

                        var rawPath = if (dataCol >= 0) {
                            try { c.getString(dataCol) } catch (_: Exception) { null }
                        } else null

                        if (rawPath.isNullOrBlank()) {
                            rawPath = if (!displayName.isNullOrBlank()) {
                                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), displayName).absolutePath
                            } else {
                                "/storage/emulated/0/Music/audio_$id.mp3"
                            }
                        }
                        val filePath = rawPath

                        val parentFolder = if (filePath.startsWith("/")) File(filePath).parent?.trimEnd('/') ?: "" else ""
                        if (parentFolder.isNotBlank() && isNormPathExcluded(normalizePath(parentFolder), normExcludedPaths)) {
                            filteredRejected++
                            continue
                        }

                        if (normIncludedPaths.isNotEmpty()) {
                            val normParent = normalizePath(parentFolder)
                            if (!isSongInIncludedFolders(normParent, normIncludedPaths)) {
                                filteredRejected++
                                continue
                            }
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

                        if (!isAudioMime && !isAudioExt && ext.isNotBlank() && !ext.equals("mp3", ignoreCase = true)) {
                            filteredRejected++
                            continue
                        }

                        val duration = if (durationCol >= 0) c.getLong(durationCol) else 0L
                        if (duration > 0 && ((ignoreAudioDurationMs > 0 && duration < ignoreAudioDurationMs) || (maxAudioDurationMs in 1 until Long.MAX_VALUE && duration > maxAudioDurationMs))) {
                            filteredRejected++
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

                        val filenameForTag = displayName ?: File(filePath).name
                        val cleanedTitle = TagCleaner.cleanTitle(rawTitle, filenameForTag)
                        val cleanedArtist = TagCleaner.cleanArtist(rawArtist)
                        val cleanedAlbum = TagCleaner.cleanAlbum(rawAlbum)
                        val format = ext.ifBlank { "MP3" }.uppercase()

                        val dedupeKey = if (id > 0) "id_$id" else "path_${normalizePath(filePath)}"
                        if (foundSongsMap.containsKey(dedupeKey)) {
                            duplicatesSkipped++
                        } else {
                            foundSongsMap[dedupeKey] = SongEntity(
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
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime > 80) {
                            lastEmitTime = now
                            val folderName = if (parentFolder.isNotBlank()) parentFolder.substringAfterLast('/').ifBlank { "Media Store" } else "Media Store"
                            _scanProgress.value = ScanProgress(
                                status = ScanStatus.SCANNING,
                                filesDiscovered = filesDiscovered,
                                filesProcessed = filesProcessed,
                                validTracksFound = foundSongsMap.size,
                                duplicatesSkipped = duplicatesSkipped,
                                filteredRejected = filteredRejected,
                                currentFolder = folderName,
                                isScanning = true,
                                isFinished = false
                            )
                        }
                    }
                }

                // 2. Direct Storage Traversal for unindexed tracks
                val retriever = MediaMetadataRetriever()
                try {
                    val rootsToScan = mutableListOf<File>()
                    if (normIncludedPaths.isNotEmpty()) {
                        includedFolders.forEach { folder ->
                            val dir = File(folder.path)
                            if (dir.exists() && dir.isDirectory) rootsToScan.add(dir)
                        }
                    } else {
                        val publicDirs = listOf(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                            File(Environment.getExternalStorageDirectory(), "Music"),
                            File(Environment.getExternalStorageDirectory(), "Download"),
                            File(Environment.getExternalStorageDirectory(), "Downloads"),
                            File(Environment.getExternalStorageDirectory(), "Songs"),
                            File(Environment.getExternalStorageDirectory(), "Audio")
                        )
                        publicDirs.forEach { dir ->
                            if (dir != null && dir.exists() && dir.isDirectory && !rootsToScan.contains(dir)) {
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
                            foundSongsMap = foundSongsMap,
                            retriever = retriever,
                            onProgress = { currentDir, _ ->
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > 80) {
                                    lastEmitTime = now
                                    _scanProgress.value = ScanProgress(
                                        status = ScanStatus.SCANNING,
                                        filesDiscovered = filesDiscovered.coerceAtLeast(foundSongsMap.size),
                                        filesProcessed = filesProcessed.coerceAtLeast(foundSongsMap.size),
                                        validTracksFound = foundSongsMap.size,
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

                // 3. Save Discovered Songs to DB
                val rawSongsList = foundSongsMap.values.toList()
                if (rawSongsList.isNotEmpty()) {
                    val existingSongs = songDao.getAllSongsOnce()
                    val existingById = existingSongs.associateBy { it.id }
                    val existingByPath = existingSongs.associateBy { normalizePath(it.path) }

                    val mergedSongsList = rawSongsList.map { song ->
                        val normP = normalizePath(song.path)
                        val existing = existingByPath[normP] ?: existingById[song.id]
                        buildOrUpdateSongEntity(
                            existing = existing,
                            id = song.id,
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
                    }

                    songDao.deleteUnusedSampleSongs()
                    songDao.insertSongs(mergedSongsList)

                    // Mark missing songs
                    val updatedSongs = songDao.getAllSongsOnce()
                    updatedSongs.forEach { dbSong ->
                        if (!dbSong.path.startsWith("sample_") && !dbSong.contentUriString.startsWith("http")) {
                            val parentFolder = File(dbSong.path).parent?.trimEnd('/') ?: ""
                            val normParent = normalizePath(parentFolder)
                            val isExcluded = isNormPathExcluded(normParent, normExcludedPaths)
                            val isNotIncluded = !isSongInIncludedFolders(normParent, normIncludedPaths)
                            val isContentUri = dbSong.contentUriString.startsWith("content://")
                            val isAccessible = if (isContentUri) true else try { File(dbSong.path).exists() } catch (_: Exception) { false }
                            val isTooShort = ignoreAudioDurationMs > 0 && dbSong.durationMs > 0 && dbSong.durationMs < ignoreAudioDurationMs
                            val isTooLong = maxAudioDurationMs in 1 until Long.MAX_VALUE && dbSong.durationMs > 0 && dbSong.durationMs > maxAudioDurationMs

                            if (isExcluded || isNotIncluded || !isAccessible || isTooShort || isTooLong) {
                                songDao.markMissing(dbSong.path)
                            }
                        }
                    }
                    songDao.deleteMissingSongs()
                }

                val finalCount = songDao.getSongCountOnce()
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
     * Fast Quick Scan: Quickly prunes missing files and indexes new audio tracks
     * without touching user-edited tags, play counts, or favorites.
     */
    suspend fun quickScanAndCleanLibrary(
        includedFolders: List<FolderEntity> = emptyList(),
        excludedFolders: List<FolderEntity> = emptyList(),
        ignoreAudioDurationMs: Long = 0L,
        maxAudioDurationMs: Long = Long.MAX_VALUE
    ) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val initialCount = songDao.getSongCountOnce()
            var filesDiscovered = 0
            var filesProcessed = 0
            var duplicatesSkipped = 0
            var filteredRejected = 0

            _scanProgress.value = ScanProgress(
                status = ScanStatus.SCANNING,
                filesDiscovered = 0,
                filesProcessed = 0,
                validTracksFound = initialCount,
                duplicatesSkipped = 0,
                filteredRejected = 0,
                currentFolder = "Quick indexing...",
                isScanning = true,
                isFinished = false
            )

            val normExcludedPaths = excludedFolders.map { normalizePath(it.path) }
            val normIncludedPaths = includedFolders.map { normalizePath(it.path) }
            var lastEmitTime = 0L

            try {
                // 1. Clean missing/excluded songs and normalize existing unedited library tracks
                val existingSongs = songDao.getAllSongsOnce()
                val songsToUpdate = mutableListOf<SongEntity>()

                for (dbSong in existingSongs) {
                    filesDiscovered++
                    filesProcessed++

                    if (!dbSong.path.startsWith("sample_") && !dbSong.contentUriString.startsWith("http")) {
                        val parentFolder = File(dbSong.path).parent?.trimEnd('/') ?: ""
                        val normParent = normalizePath(parentFolder)
                        val isExcluded = isNormPathExcluded(normParent, normExcludedPaths)
                        val isNotIncluded = !isSongInIncludedFolders(normParent, normIncludedPaths)
                        val isContentUri = dbSong.contentUriString.startsWith("content://")
                        val isAccessible = if (isContentUri) true else try { File(dbSong.path).exists() } catch (_: Exception) { false }
                        val isTooShort = ignoreAudioDurationMs > 0 && dbSong.durationMs > 0 && dbSong.durationMs < ignoreAudioDurationMs
                        val isTooLong = maxAudioDurationMs in 1 until Long.MAX_VALUE && dbSong.durationMs > 0 && dbSong.durationMs > maxAudioDurationMs

                        if (isExcluded || isNotIncluded || !isAccessible || isTooShort || isTooLong) {
                            songDao.markMissing(dbSong.path)
                            filteredRejected++
                            continue
                        }
                    }

                    // Check if existing unedited song needs normalization
                    val isManual = dbSong.isManuallyEdited || com.example.util.ManualTagStore.isManuallyEdited(context, dbSong.id, dbSong.path)
                    if (!isManual) {
                        val filename = File(dbSong.path).name
                        val normalizedTitle = TagCleaner.cleanTitle(dbSong.title, filename)
                        val normalizedArtist = TagCleaner.cleanArtist(dbSong.artist)
                        val normalizedAlbum = TagCleaner.cleanAlbum(dbSong.album)

                        if (normalizedTitle != dbSong.title || normalizedArtist != dbSong.artist || normalizedAlbum != dbSong.album) {
                            val updatedSong = dbSong.copy(
                                title = normalizedTitle,
                                artist = normalizedArtist,
                                album = normalizedAlbum
                            )
                            songsToUpdate.add(updatedSong)
                        }
                    }
                }

                if (songsToUpdate.isNotEmpty()) {
                    for (song in songsToUpdate) {
                        songDao.updateSong(song)
                    }
                }
                songDao.deleteMissingSongs()

                // Refresh remaining existing songs map after cleaning
                val refreshedExisting = songDao.getAllSongsOnce()
                val existingByPath = refreshedExisting.associateBy { normalizePath(it.path) }
                val existingById = refreshedExisting.associateBy { it.id }

                // 2. Scan MediaStore for newly added tracks
                val newSongsList = mutableListOf<SongEntity>()
                val projectionList = arrayOf(
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

                    while (c.moveToNext()) {
                        filesDiscovered++
                        filesProcessed++

                        val mediaStoreId = if (idCol >= 0) c.getLong(idCol) else System.currentTimeMillis()
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
                        val displayName = if (nameCol >= 0) {
                            try { c.getString(nameCol) } catch (_: Exception) { null }
                        } else null

                        var rawPath = if (dataCol >= 0) {
                            try { c.getString(dataCol) } catch (_: Exception) { null }
                        } else null

                        if (rawPath.isNullOrBlank()) {
                            rawPath = if (!displayName.isNullOrBlank()) {
                                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), displayName).absolutePath
                            } else {
                                "/storage/emulated/0/Music/audio_$mediaStoreId.mp3"
                            }
                        }
                        val filePath = rawPath
                        val normP = normalizePath(filePath)

                        if (existingByPath.containsKey(normP) || existingById.containsKey(mediaStoreId)) {
                            duplicatesSkipped++
                            continue
                        }

                        val parentFolder = if (filePath.startsWith("/")) File(filePath).parent?.trimEnd('/') ?: "" else ""
                        if (parentFolder.isNotBlank() && isNormPathExcluded(normalizePath(parentFolder), normExcludedPaths)) {
                            filteredRejected++
                            continue
                        }

                        if (normIncludedPaths.isNotEmpty()) {
                            val normParent = normalizePath(parentFolder)
                            if (!isSongInIncludedFolders(normParent, normIncludedPaths)) {
                                filteredRejected++
                                continue
                            }
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

                        if (!isAudioMime && !isAudioExt && ext.isNotBlank() && !ext.equals("mp3", ignoreCase = true)) {
                            filteredRejected++
                            continue
                        }

                        val dur = if (durationCol >= 0) c.getLong(durationCol) else 0L
                        if (dur > 0L && ((ignoreAudioDurationMs > 0 && dur < ignoreAudioDurationMs) || (maxAudioDurationMs in 1 until Long.MAX_VALUE && dur > maxAudioDurationMs))) {
                            filteredRejected++
                            continue
                        }

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
                        newSongsList.add(newSong)

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime > 80) {
                            lastEmitTime = now
                            val folderName = if (parentFolder.isNotBlank()) parentFolder.substringAfterLast('/').ifBlank { "Quick Scan" } else "Quick Scan"
                            _scanProgress.value = ScanProgress(
                                status = ScanStatus.SCANNING,
                                filesDiscovered = filesDiscovered,
                                filesProcessed = filesProcessed,
                                validTracksFound = existingSongs.size + newSongsList.size,
                                duplicatesSkipped = duplicatesSkipped,
                                filteredRejected = filteredRejected,
                                currentFolder = folderName,
                                isScanning = true,
                                isFinished = false
                            )
                        }
                    }
                }

                // 3. Fast direct check on common storage music directories
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
                            onProgress = { currentDir, _ ->
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > 80) {
                                    lastEmitTime = now
                                    val currentValid = existingSongs.size + newSongsList.size
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
                } catch (_: Exception) {}

                if (newSongsList.isNotEmpty()) {
                    songDao.insertSongs(newSongsList)
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

    private fun isDirInIncludedFolders(normDir: String, normIncludedPaths: List<String>): Boolean {
        if (normIncludedPaths.isEmpty()) return true
        return normIncludedPaths.any { normIncluded ->
            normDir == normIncluded || normDir.startsWith("$normIncluded/") || normIncluded.startsWith("$normDir/")
        }
    }
}
