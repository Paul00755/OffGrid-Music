package com.example.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.example.ui.components.ArtworkResolutionCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

object ArtworkManager {

    private const val CACHE_DIR_NAME = "artworks"
    private const val SONG_ARTWORKS_DIR_NAME = "song_artworks"
    private const val NOTIFICATION_ARTWORK_MISS_TTL_MS = 5 * 60 * 1000L
    private const val MAX_NOTIFICATION_ARTWORK_MISSES = 1024

    // Per-song synchronization locks to prevent concurrent threads from corrupting or truncating JPEG files
    private val songLocks = ConcurrentHashMap<Long, Any>()
    private fun lockForSong(songId: Long): Any = songLocks.computeIfAbsent(songId) { Any() }

    // Bounded semaphore for MediaMetadataRetriever so background preloads never starve visible UI artwork
    private val mmrSemaphore = Semaphore(8)

    // In-memory index of verified valid image files (absolutePath -> byteLength) to eliminate repeated RandomAccessFile syscalls
    private val verifiedValidFiles = ConcurrentHashMap<String, Long>()

    private val notificationArtCache = object : android.util.LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }
    private val notificationBitmapCache = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }
    /** Avoid repeating expensive MediaMetadataRetriever work when a track has no artwork. */
    private val notificationArtworkMisses = ConcurrentHashMap<String, Long>()

    private fun notificationArtworkKey(songId: Long, filePath: String?): String =
        ArtworkResolutionCache.trackIdentity(songId, filePath)

    /**
     * Validates that a cached JPEG/PNG file on disk is complete and not a half-written truncated JPEG
     * (which causes Skia/Coil to render the top half and fill the bottom half with solid black/green).
     * Automatically deletes truncated/corrupted cache files so they are cleanly re-extracted.
     */
    fun isValidCompleteImageFile(file: File?): Boolean {
        if (file == null) return false
        val absPath = file.absolutePath
        val cachedLen = verifiedValidFiles[absPath]
        if (cachedLen != null && cachedLen >= 256L) {
            return true
        }
        if (!file.exists() || !file.isFile) {
            verifiedValidFiles.remove(absPath)
            return false
        }
        val len = file.length()
        if (len < 256L) {
            verifiedValidFiles.remove(absPath)
            try { file.delete() } catch (_: Exception) {}
            return false
        }
        val nameLower = file.name.lowercase()
        if (nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg")) {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val b0 = raf.read()
                    val b1 = raf.read()
                    // Check JPEG SOI (0xFF 0xD8) or PNG header (0x89 0x50) or WebP ('R' 'I')
                    val isJpeg = (b0 == 0xFF && b1 == 0xD8)
                    val isPng = (b0 == 0x89 && b1 == 0x50)
                    val isWebp = (b0 == 0x52 && b1 == 0x49)
                    if (!isJpeg && !isPng && !isWebp) {
                        verifiedValidFiles.remove(absPath)
                        try { file.delete() } catch (_: Exception) {}
                        return false
                    }
                    if (isJpeg) {
                        val tailSize = minOf(32L, len).toInt()
                        raf.seek(len - tailSize)
                        val tail = ByteArray(tailSize)
                        raf.readFully(tail)
                        var hasEoi = false
                        for (i in 0 until tailSize - 1) {
                            if ((tail[i].toInt() and 0xFF) == 0xFF && (tail[i + 1].toInt() and 0xFF) == 0xD9) {
                                hasEoi = true
                                break
                            }
                        }
                        if (!hasEoi) {
                            verifiedValidFiles.remove(absPath)
                            // Truncated JPEG from an interrupted write! Delete it so it gets re-extracted cleanly.
                            try { file.delete() } catch (_: Exception) {}
                            return false
                        }
                    }
                }
            } catch (_: Exception) {
                return false
            }
        }
        verifiedValidFiles[absPath] = len
        return true
    }

    /**
     * Atomically writes bytes to `destFile` via a temporary sibling file and `renameTo`
     * so no reader (Coil or UI thread) ever reads a partially written image file.
     */
    private fun writeBytesAtomically(destFile: File, bytes: ByteArray, songId: Long = 0L): Boolean {
        if (bytes.isEmpty()) return false
        synchronized(lockForSong(songId)) {
            val parent = destFile.parentFile ?: return false
            if (!parent.exists()) parent.mkdirs()
            val tempFile = File(parent, ".${destFile.name}.${Thread.currentThread().id}.${System.nanoTime()}.tmp")
            return try {
                FileOutputStream(tempFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    try { fos.fd.sync() } catch (_: Exception) {}
                }
                if (tempFile.length() > 0L) {
                    if (!tempFile.renameTo(destFile)) {
                        tempFile.copyTo(destFile, overwrite = true)
                        tempFile.delete()
                    }
                    verifiedValidFiles[destFile.absolutePath] = bytes.size.toLong()
                    true
                } else {
                    tempFile.delete()
                    false
                }
            } catch (_: Exception) {
                try { tempFile.delete() } catch (_: Exception) {}
                false
            }
        }
    }

    private fun writeBitmapAtomically(destFile: File, bitmap: Bitmap, quality: Int = 90, songId: Long = 0L): ByteArray? {
        return try {
            val baos = ByteArrayOutputStream(32 * 1024)
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val bytes = baos.toByteArray()
            if (ok && bytes.size > 256 && writeBytesAtomically(destFile, bytes, songId)) {
                bytes
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clearMemoryCache() {
        synchronized(notificationArtCache) {
            notificationArtCache.evictAll()
        }
        synchronized(notificationBitmapCache) {
            notificationBitmapCache.evictAll()
        }
    }

    fun getCachedNotificationArtwork(songId: Long, filePath: String? = null): ByteArray? {
        synchronized(notificationArtCache) {
            return notificationArtCache.get(notificationArtworkKey(songId, filePath))
        }
    }

    fun getCachedNotificationBitmap(songId: Long, filePath: String? = null): Bitmap? {
        synchronized(notificationBitmapCache) {
            val bmp = notificationBitmapCache.get(notificationArtworkKey(songId, filePath))
            return if (bmp != null && !bmp.isRecycled) bmp else null
        }
    }

    fun putCachedNotificationBitmap(songId: Long, bitmap: Bitmap, filePath: String? = null) {
        if (songId <= 0L || bitmap.isRecycled) return
        val key = notificationArtworkKey(songId, filePath)
        notificationArtworkMisses.remove(key)
        synchronized(notificationBitmapCache) {
            notificationBitmapCache.put(key, bitmap)
        }
    }

    fun invalidateNotificationArtwork(songId: Long, filePath: String? = null) {
        val keys = if (filePath.isNullOrBlank()) {
            listOf(notificationArtworkKey(songId, null)) +
                synchronized(notificationArtCache) { notificationArtCache.snapshot().keys.filter { it.startsWith("id:$songId:") } }
        } else {
            listOf(notificationArtworkKey(songId, filePath), notificationArtworkKey(songId, null))
        }
        if (filePath.isNullOrBlank()) {
            notificationArtworkMisses.keys.filter { it.startsWith("id:$songId:") }.forEach(notificationArtworkMisses::remove)
        } else {
            keys.forEach(notificationArtworkMisses::remove)
        }
        synchronized(notificationArtCache) {
            keys.forEach(notificationArtCache::remove)
        }
        synchronized(notificationBitmapCache) {
            keys.forEach(notificationBitmapCache::remove)
        }
    }

    fun putCachedNotificationArtwork(songId: Long, bytes: ByteArray, filePath: String? = null) {
        if (songId <= 0L || bytes.isEmpty()) return
        notificationArtworkMisses.remove(notificationArtworkKey(songId, filePath))
        synchronized(notificationArtCache) {
            notificationArtCache.put(notificationArtworkKey(songId, filePath), bytes)
        }
    }

    fun putNotificationArtwork(songId: Long, bytes: ByteArray, bitmap: Bitmap, filePath: String? = null) {
        if (songId <= 0L) return
        val key = notificationArtworkKey(songId, filePath)
        notificationArtworkMisses.remove(key)
        synchronized(notificationArtCache) {
            notificationArtCache.put(key, bytes)
        }
        if (!bitmap.isRecycled) {
            synchronized(notificationBitmapCache) {
                notificationBitmapCache.put(key, bitmap)
            }
        }
    }

    private val thumbnailFileCache = android.util.LruCache<String, String>(1000)

    /**
     * Finds image artwork from local device .thumbnail / .thumbnails files and companion cover files
     * strictly associated with this specific track (never generic folder-wide cover.jpg / folder.jpg).
     */
    fun findThumbnailArtwork(filePath: String, songId: Long = 0L, context: Context? = null): File? {
        if (filePath.isBlank() && songId <= 0L) return null
        val cacheLookupKey = "${songId}_$filePath"
        synchronized(thumbnailFileCache) {
            val cachedPath = thumbnailFileCache.get(cacheLookupKey)
            if (cachedPath != null) {
                return if (cachedPath.isNotEmpty()) {
                    val f = File(cachedPath)
                    if (isValidCompleteImageFile(f)) f else null
                } else null
            }
        }

        return try {
            val pathHash = if (filePath.isNotBlank()) {
                filePath.trim().lowercase().hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL
            } else {
                songId and 0x7FFFFFFF_FFFFFFFFL
            }
            val uniqueTrackPrefix = if (songId > 0L) "track_${songId}_${pathHash}" else "track_path_${pathHash}"

            // 0. Check app-scoped .thumbnail directories first (safe under Android scoped storage)
            if (context != null) {
                val appThumbDirs = listOfNotNull(
                    File(context.filesDir, ".thumbnail"),
                    context.getExternalFilesDir(".thumbnail")
                )
                for (dir in appThumbDirs) {
                    if (dir.exists() && dir.isDirectory) {
                        val exact = File(dir, "$uniqueTrackPrefix.jpg")
                        if (isValidCompleteImageFile(exact)) {
                            synchronized(thumbnailFileCache) {
                                thumbnailFileCache.put(cacheLookupKey, exact.absolutePath)
                            }
                            return exact
                        }
                        val timestamped = dir.listFiles { file ->
                            (file.name.startsWith("${uniqueTrackPrefix}_") ||
                                (songId > 0L && file.name.startsWith("track_${songId}_"))) &&
                                isValidCompleteImageFile(file)
                        }?.maxByOrNull { it.lastModified() }
                        if (timestamped != null) {
                            synchronized(thumbnailFileCache) {
                                thumbnailFileCache.put(cacheLookupKey, timestamped.absolutePath)
                            }
                            return timestamped
                        }
                    }
                }
            }

            if (filePath.isBlank()) return null
            val audioFile = File(filePath)
            val parentDir = audioFile.parentFile ?: return null
            if (!parentDir.exists() || !parentDir.isDirectory) return null

            val baseName = audioFile.nameWithoutExtension

            // 1. Check within hidden .thumbnail / .thumbnails folder in parent directory for per-track files
            var resolvedFile: File? = null
            val thumbDirs = listOf(
                File(parentDir, ".thumbnail"),
                File(parentDir, ".thumbnails")
            )

            for (td in thumbDirs) {
                if (td.exists() && td.isDirectory) {
                    val exactTrack = File(td, "$uniqueTrackPrefix.jpg")
                    if (isValidCompleteImageFile(exactTrack)) {
                        resolvedFile = exactTrack
                        break
                    }
                    val matchingFile = td.listFiles { file ->
                        isValidCompleteImageFile(file) && (
                            file.name.startsWith("${uniqueTrackPrefix}_", ignoreCase = true) ||
                                (songId > 0L && file.name.startsWith("track_${songId}_", ignoreCase = true)) ||
                                file.nameWithoutExtension.equals(baseName, ignoreCase = true)
                            )
                    }?.maxByOrNull { it.lastModified() }
                    if (matchingFile != null) {
                        resolvedFile = matchingFile
                        break
                    }
                }
            }

            // 2. Direct song-specific companion image files with exact baseName in the same directory
            //    (Never match shared cover.jpg / folder.jpg / album.jpg / front.jpg so tracks in shared folders never collide)
            if (resolvedFile == null && baseName.isNotBlank()) {
                val candidateFileNames = listOf(
                    "$baseName.jpg",
                    "$baseName.png",
                    "$baseName.jpeg",
                    "$baseName.webp",
                    "$baseName.cover.jpg",
                    "$baseName.thumbnail.jpg",
                    "$baseName.thumbnail.png",
                    ".$baseName.thumbnail"
                )
                for (cand in candidateFileNames) {
                    val f = File(parentDir, cand)
                    if (isValidCompleteImageFile(f)) {
                        resolvedFile = f
                        break
                    }
                }
            }

            synchronized(thumbnailFileCache) {
                thumbnailFileCache.put(cacheLookupKey, resolvedFile?.absolutePath ?: "")
            }

            resolvedFile
        } catch (_: Exception) {
            synchronized(thumbnailFileCache) {
                thumbnailFileCache.put(cacheLookupKey, "")
            }
            null
        }
    }

    fun decodeBitmapFromUri(context: Context, uriString: String?, targetSize: Int = 512): Bitmap? {
        if (uriString.isNullOrBlank()) return null
        return try {
            val stream: java.io.InputStream? = when {
                uriString.startsWith("file://") -> {
                    val p = Uri.parse(uriString).path
                    val f = if (p != null) File(p) else null
                    if (isValidCompleteImageFile(f)) java.io.FileInputStream(f!!) else null
                }
                uriString.startsWith("/") -> {
                    val f = File(uriString)
                    if (isValidCompleteImageFile(f)) java.io.FileInputStream(f) else null
                }
                uriString.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(uriString))
                }
                uriString.startsWith("http://") || uriString.startsWith("https://") -> {
                    val conn = java.net.URL(uriString).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.inputStream
                }
                else -> {
                    val f = File(uriString)
                    if (isValidCompleteImageFile(f)) java.io.FileInputStream(f) else context.contentResolver.openInputStream(Uri.parse(uriString))
                }
            }
            stream?.use { input ->
                val bytes = input.readBytes()
                if (bytes.isEmpty()) return null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun getNotificationArtworkBytes(
        context: Context,
        songId: Long,
        filePath: String,
        albumId: Long,
        artworkUriStr: String? = null
    ): ByteArray? {
        return synchronized(lockForSong(songId)) {
            val key = notificationArtworkKey(songId, filePath)
            val cached = synchronized(notificationArtCache) { notificationArtCache.get(key) }
            if (cached != null && cached.isNotEmpty()) {
                notificationArtworkMisses.remove(key)
                return@synchronized cached
            }

            val now = android.os.SystemClock.elapsedRealtime()
            val previousMiss = notificationArtworkMisses[key]
            if (previousMiss != null) {
                if (now - previousMiss < NOTIFICATION_ARTWORK_MISS_TTL_MS) return@synchronized null
                notificationArtworkMisses.remove(key, previousMiss)
            }

            val effectiveUriStr = if (!artworkUriStr.isNullOrBlank()) {
                artworkUriStr
            } else {
                ArtworkResolutionCache.getMemory(songId, filePath)
                    ?: ArtworkResolutionCache.getOrCheckDisk(context, songId, filePath)
            }

            val rawBytes = getOrExtractArtworkBytesSync(context, songId, filePath, albumId, effectiveUriStr)
            if (rawBytes == null || rawBytes.isEmpty()) {
                notificationArtworkMisses[key] = now
                if (notificationArtworkMisses.size > MAX_NOTIFICATION_ARTWORK_MISSES) {
                    notificationArtworkMisses.minByOrNull { it.value }?.let { notificationArtworkMisses.remove(it.key, it.value) }
                }
                return@synchronized null
            }

            val finalBytes = try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
                options.inSampleSize = calculateInSampleSize(options, 320, 320)
                options.inJustDecodeBounds = false
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
                if (bmp != null) {
                    putCachedNotificationBitmap(songId, bmp, filePath)
                    val stream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                    stream.toByteArray()
                } else {
                    rawBytes
                }
            } catch (_: Throwable) {
                rawBytes
            }

            notificationArtworkMisses.remove(key)
            synchronized(notificationArtCache) { notificationArtCache.put(key, finalBytes) }
            finalBytes
        }
    }

    fun saveFetchedArtworkLocally(
        context: Context,
        songId: Long,
        url: String,
        filePath: String? = null,
        isHighRes: Boolean = false,
        expectedArtworkGeneration: Long? = null
    ): String? {
        if (songId <= 0L || url.isBlank()) return null
        val safePath = filePath.orEmpty()
        // Never overwrite an explicit user-selected manual cover
        if (ManualTagStore.isArtworkManuallyEdited(context, songId, safePath)) {
            return ManualTagStore.getManualMetadata(context, songId, safePath)?.artworkUri
                ?: ArtworkResolutionCache.getManualCoverUri(context, songId, filePath)
        }
        val startGen = expectedArtworkGeneration ?: ManualTagStore.getArtworkEditGeneration(context, songId, safePath)
        if (ManualTagStore.getArtworkEditGeneration(context, songId, safePath) != startGen) {
            return ManualTagStore.getManualMetadata(context, songId, safePath)?.artworkUri
        }
        return try {
            val destDir = File(context.filesDir, CACHE_DIR_NAME)
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, if (filePath.isNullOrBlank()) "$songId.jpg" else ArtworkResolutionCache.trackFileName(songId, filePath))

            val rawBytes = if (url.startsWith("http://") || url.startsWith("https://")) {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.inputStream.use { it.readBytes() }
            } else if (url.startsWith("file://")) {
                val f = File(Uri.parse(url).path ?: "")
                if (isValidCompleteImageFile(f)) f.readBytes() else null
            } else null

            // Re-check manual edit state and generation after network/disk read to prevent concurrent race overwrite
            if (ManualTagStore.isArtworkManuallyEdited(context, songId, safePath) ||
                ManualTagStore.getArtworkEditGeneration(context, songId, safePath) != startGen
            ) {
                return ManualTagStore.getManualMetadata(context, songId, safePath)?.artworkUri
                    ?: ArtworkResolutionCache.getManualCoverUri(context, songId, filePath)
            }

            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
                if (bmp != null) {
                    val savedBytes = writeBitmapAtomically(destFile, bmp, 92, songId)
                    if (savedBytes != null) {
                        putNotificationArtwork(songId, savedBytes, bmp, filePath)
                        val localUri = Uri.fromFile(destFile).toString()
                        val achievedHighRes = isHighRes || bmp.width >= 600 || bmp.height >= 600 ||
                            url.contains("1000x1000") || url.contains("600x600") || url.contains("cover_xl")
                        if (achievedHighRes) {
                            ArtworkResolutionCache.markHighResArtworkCached(context, songId, filePath, localUri)
                        }
                        ArtworkResolutionCache.cacheArtwork(songId, filePath, localUri)
                        ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, localUri, filePath, forceReplace = true)
                        ArtworkResolutionCache.notifyArtworkChanged(songId, localUri, filePath)
                        try {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    com.example.data.local.AppDatabase.getInstance(context).songDao().updateSongArtwork(songId, localUri)
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                        com.example.service.PlaybackManager.activeInstance?.let { pm ->
                            val currentSong = pm.playbackState.value.currentSong
                            val inQueue = pm.playbackState.value.queue.any { it.id == songId && (filePath.isNullOrBlank() || it.path == filePath) }
                            if ((currentSong?.id == songId && (filePath.isNullOrBlank() || currentSong.path == filePath)) || inQueue) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    pm.updateCurrentSongArtwork(songId, localUri, filePath)
                                }
                            }
                        }
                        localUri
                    } else null
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun invalidateSongArtwork(context: Context, songId: Long, deleteFiles: Boolean = false) {
        try {
            invalidateNotificationArtwork(songId)
            ArtworkResolutionCache.invalidate(songId)
            if (deleteFiles) {
                synchronized(lockForSong(songId)) {
                    val cacheFile = File(File(context.cacheDir, CACHE_DIR_NAME), "$songId.jpg")
                    verifiedValidFiles.remove(cacheFile.absolutePath)
                    if (cacheFile.exists()) cacheFile.delete()
                    val songArtFile = File(File(context.cacheDir, SONG_ARTWORKS_DIR_NAME), "$songId.jpg")
                    verifiedValidFiles.remove(songArtFile.absolutePath)
                    if (songArtFile.exists()) songArtFile.delete()
                    val persistentFile = File(File(context.filesDir, CACHE_DIR_NAME), "$songId.jpg")
                    verifiedValidFiles.remove(persistentFile.absolutePath)
                    if (persistentFile.exists()) persistentFile.delete()
                    val persistentSongArt = File(File(context.filesDir, SONG_ARTWORKS_DIR_NAME), "$songId.jpg")
                    verifiedValidFiles.remove(persistentSongArt.absolutePath)
                    if (persistentSongArt.exists()) persistentSongArt.delete()
                }
            }
            try {
                val loader = coil.Coil.imageLoader(context)
                loader.memoryCache?.keys?.filter { it.key.contains("art_${songId}_") }?.forEach { key ->
                    loader.memoryCache?.remove(key)
                }
            } catch (_: Throwable) {}
        } catch (_: Exception) {}
    }

    /**
     * Note: Android's ContentResolver.loadThumbnail(songUri) delegates to MediaProvider's shared
     * ALBUM_ID table, which returns folder-wide artwork across unrelated tracks in the same folder
     * (e.g. "Download"). Always return null here so only genuine per-track embedded ID3/MP4 pictures
     * (extracted via MediaMetadataRetriever) or explicit per-song covers are used.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun extractFastMediaStoreBitmap(context: Context, songId: Long, albumId: Long): Bitmap? {
        return null
    }

    fun getOrExtractArtworkBytesSync(
        context: Context,
        songId: Long,
        filePath: String,
        albumId: Long,
        artworkUriStr: String? = null
    ): ByteArray? {
        // 0. Check manual song_covers first (highest priority)
        val coversDir = File(context.filesDir, "song_covers")
        if (coversDir.exists()) {
            val manualCover = coversDir.listFiles { file ->
                (file.name.startsWith("song_${songId}_") || file.name == "song_$songId.jpg") && isValidCompleteImageFile(file)
            }?.maxByOrNull { it.lastModified() }
            if (manualCover != null) {
                try { return manualCover.readBytes() } catch (_: Exception) {}
            }
        }

        // 1. If artworkUriStr is given and points to a valid file or content
        if (!artworkUriStr.isNullOrBlank()) {
            try {
                if (artworkUriStr.startsWith("file://")) {
                    val f = File(Uri.parse(artworkUriStr).path ?: "")
                    if (isValidCompleteImageFile(f)) {
                        return f.readBytes()
                    }
                } else if (artworkUriStr.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(artworkUriStr))?.use {
                        val bytes = it.readBytes()
                        if (bytes.isNotEmpty()) return bytes
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Check persistent & cached artwork files first before expensive extraction
        val persistentDir = File(context.filesDir, CACHE_DIR_NAME)
        if (!persistentDir.exists()) persistentDir.mkdirs()
        val persistentFile = File(persistentDir, "$songId.jpg")
        if (isValidCompleteImageFile(persistentFile)) {
            try { return persistentFile.readBytes() } catch (_: Exception) {}
        }

        val persistentSongArt = File(File(context.filesDir, SONG_ARTWORKS_DIR_NAME), "$songId.jpg")
        if (isValidCompleteImageFile(persistentSongArt)) {
            try { return persistentSongArt.readBytes() } catch (_: Exception) {}
        }

        val cacheFile = File(File(context.cacheDir, CACHE_DIR_NAME), "$songId.jpg")
        if (isValidCompleteImageFile(cacheFile)) {
            try {
                val bytes = cacheFile.readBytes()
                writeBytesAtomically(persistentFile, bytes, songId)
                return bytes
            } catch (_: Exception) {}
        }

        // 3. Check local device .thumbnail / companion file from user's storage
        if (filePath.isNotBlank() || songId > 0L) {
            val thumbFile = findThumbnailArtwork(filePath, songId, context)
            if (thumbFile != null && isValidCompleteImageFile(thumbFile)) {
                try {
                    val bytes = thumbFile.readBytes()
                    if (bytes.isNotEmpty()) {
                        writeBytesAtomically(persistentFile, bytes, songId)
                        return bytes
                    }
                } catch (_: Exception) {}
            }
        }

        // 4. Extract Embedded Picture from actual audio file via MediaMetadataRetriever
        if (songId > 0L || filePath.isNotBlank()) {
            val mmr = MediaMetadataRetriever()
            try {
                var setSuccess = false
                if (filePath.isNotBlank()) {
                    val f = File(filePath)
                    if (f.exists()) {
                        try {
                            mmr.setDataSource(filePath)
                            setSuccess = true
                        } catch (_: Exception) {}
                    }
                }
                if (!setSuccess && songId > 0L) {
                    try {
                        val songContentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
                        mmr.setDataSource(context, songContentUri)
                        setSuccess = true
                    } catch (_: Exception) {}
                }
                if (setSuccess) {
                    val artBytes = mmr.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, boundsOpts)
                        val sampleSize = calculateInSampleSize(boundsOpts, 512, 512)
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        val decodedBmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)
                        if (decodedBmp != null) {
                            val cleanJpeg = writeBitmapAtomically(persistentFile, decodedBmp, 90, songId)
                            putCachedNotificationBitmap(songId, decodedBmp, filePath)
                            return cleanJpeg ?: artBytes
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
        }

        return null
    }

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int = 512, reqHeight: Int = 512): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    fun purgePollutedArtworkCache(context: Context) {
        // Scan and remove only truncated/corrupted partial JPEGs while preserving all valid covers
        try {
            val artworksDir = File(context.filesDir, CACHE_DIR_NAME)
            if (artworksDir.exists()) {
                artworksDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".tmp")) {
                        try { file.delete() } catch (_: Exception) {}
                    } else {
                        isValidCompleteImageFile(file)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun getOrExtractArtworkUri(
        context: Context,
        songId: Long,
        filePath: String,
        albumId: Long,
        albumName: String = ""
    ): String? = withContext(Dispatchers.IO) {
        // 0. Check in-memory cache first
        if (songId > 0L) {
            val mem = ArtworkResolutionCache.getMemory(songId, filePath)
            if (!mem.isNullOrBlank()) return@withContext mem
        }

        // 1. Check manual song_covers first (highest priority, user-explicit)
        if (ManualTagStore.isArtworkManuallyEdited(context, songId, filePath)) {
            val manualMetaArt = ManualTagStore.getManualMetadata(context, songId, filePath)?.artworkUri
            if (!manualMetaArt.isNullOrBlank()) {
                if (songId > 0L) ArtworkResolutionCache.cacheArtwork(songId, filePath, manualMetaArt)
                return@withContext manualMetaArt
            }
            val manualCoverUri = ArtworkResolutionCache.getManualCoverUri(context, songId, filePath)
            if (!manualCoverUri.isNullOrBlank()) {
                if (songId > 0L) ArtworkResolutionCache.cacheArtwork(songId, filePath, manualCoverUri)
                return@withContext manualCoverUri
            }
        }

        ArtworkResolutionCache.getOrCheckDisk(context, songId, filePath)?.let { return@withContext it }

        val persistentDir = File(context.filesDir, CACHE_DIR_NAME)
        if (!persistentDir.exists()) persistentDir.mkdirs()
        val persistentFile = File(persistentDir, ArtworkResolutionCache.trackFileName(songId, filePath))

        // 2. Fast-path: If already extracted & verified valid in persistent storage, return immediately!
        if (songId > 0L && isValidCompleteImageFile(persistentFile)) {
            val uriStr = Uri.fromFile(persistentFile).toString()
            ArtworkResolutionCache.cacheArtwork(songId, filePath, uriStr)
            return@withContext uriStr
        }

        // 3. Extract with bounded concurrency to prevent IO starvation during fast scroll
        mmrSemaphore.withPermit {
            // Re-check after acquiring permit in case another coroutine just extracted it
            if (songId > 0L && isValidCompleteImageFile(persistentFile)) {
                val uriStr = Uri.fromFile(persistentFile).toString()
                ArtworkResolutionCache.cacheArtwork(songId, filePath, uriStr)
                return@withPermit uriStr
            }

            // 3A. Embedded picture from the actual audio file
            if (filePath.isNotBlank()) {
                val audioFile = File(filePath)
                if (audioFile.exists() && audioFile.length() > 1024L) {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(filePath)
                        val artBytes = mmr.embeddedPicture
                        if (artBytes != null && artBytes.isNotEmpty()) {
                            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, boundsOpts)
                            val sampleSize = calculateInSampleSize(boundsOpts, 512, 512)
                            val opts = BitmapFactory.Options().apply {
                                inSampleSize = sampleSize
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)
                            if (bitmap != null) {
                                val savedBytes = writeBitmapAtomically(persistentFile, bitmap, 90, songId)
                                if (savedBytes != null) {
                                    putNotificationArtwork(songId, savedBytes, bitmap, filePath)
                                    val uriStr = Uri.fromFile(persistentFile).toString()
                                    ArtworkResolutionCache.cacheArtwork(songId, filePath, uriStr)
                                    return@withPermit uriStr
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { mmr.release() } catch (_: Throwable) {}
                    }
                }
            }

            if (songId > 0L) {
                val mmr = MediaMetadataRetriever()
                try {
                    val songContentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
                    mmr.setDataSource(context, songContentUri)
                    val artBytes = mmr.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, boundsOpts)
                        val sampleSize = calculateInSampleSize(boundsOpts, 512, 512)
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)
                        if (bitmap != null) {
                            val savedBytes = writeBitmapAtomically(persistentFile, bitmap, 90, songId)
                            if (savedBytes != null) {
                                putNotificationArtwork(songId, savedBytes, bitmap, filePath)
                                val uriStr = Uri.fromFile(persistentFile).toString()
                                ArtworkResolutionCache.cacheArtwork(songId, filePath, uriStr)
                                return@withPermit uriStr
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { mmr.release() } catch (_: Throwable) {}
                }
            }

            // 3B. Fast MediaStore thumbnail / albumart fallback
            val mediaStoreBmp = extractFastMediaStoreBitmap(context, songId, albumId)
            if (mediaStoreBmp != null && songId > 0L) {
                val savedBytes = writeBitmapAtomically(persistentFile, mediaStoreBmp, 90, songId)
                if (savedBytes != null) {
                    putNotificationArtwork(songId, savedBytes, mediaStoreBmp, filePath)
                    val uriStr = Uri.fromFile(persistentFile).toString()
                    ArtworkResolutionCache.cacheArtwork(songId, filePath, uriStr)
                    return@withPermit uriStr
                }
            }

            // 3C. Song-specific local .thumbnail / companion file
            if (filePath.isNotBlank() || songId > 0L) {
                val thumbFile = findThumbnailArtwork(filePath, songId, context)
                if (thumbFile != null && isValidCompleteImageFile(thumbFile)) {
                    val thumbUri = Uri.fromFile(thumbFile).toString()
                    if (songId > 0L) ArtworkResolutionCache.cacheArtwork(songId, filePath, thumbUri)
                    return@withPermit thumbUri
                }
            }

            null
        }
    }

    suspend fun persistSongCover(context: Context, songId: Long, uriString: String?, songPath: String? = null): String? = withContext(Dispatchers.IO) {
        if (uriString.isNullOrBlank()) return@withContext null

        try {
            val coversDir = File(context.filesDir, "song_covers")
            if (!coversDir.exists()) coversDir.mkdirs()

            val appThumbnailDir = File(context.filesDir, ".thumbnail")
            if (!appThumbnailDir.exists()) appThumbnailDir.mkdirs()
            try { File(appThumbnailDir, ".nomedia").createNewFile() } catch (_: Exception) {}

            val extThumbnailDir = context.getExternalFilesDir(".thumbnail")?.also { dir ->
                if (!dir.exists()) dir.mkdirs()
                try { File(dir, ".nomedia").createNewFile() } catch (_: Exception) {}
            }

            val pathHash = if (!songPath.isNullOrBlank()) {
                ArtworkResolutionCache.trackIdentity(songId, songPath).substringAfterLast(':')
            } else {
                (songId and 0x7FFFFFFF_FFFFFFFFL).toString()
            }
            val uniqueThumbBase = "track_${songId}_${pathHash}"

            val timestamp = System.currentTimeMillis()
            val destFile = File(appThumbnailDir, "${uniqueThumbBase}_$timestamp.jpg")
            val stableThumbFile = File(appThumbnailDir, "$uniqueThumbBase.jpg")
            val legacyDestFile = File(coversDir, "song_${songId}_$timestamp.jpg")
            val stableFile = File(coversDir, "song_$songId.jpg")

            val rawBytes: ByteArray? = try {
                if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
                    val url = java.net.URL(uriString)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.inputStream.use { it.readBytes() }
                } else if (uriString.startsWith("file://")) {
                    val p = Uri.parse(uriString).path
                    if (p != null) {
                        val f = File(p)
                        if (f.exists() && f.length() > 0) f.readBytes() else null
                    } else null
                } else {
                    val inputUri = Uri.parse(uriString)
                    context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
                }
            } catch (_: Exception) {
                null
            }

            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
                val sampleSize = calculateInSampleSize(boundsOpts, 800, 800)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)

                // Delete older timestamped cover files for this song only after decoding the new cover
                appThumbnailDir.listFiles { file ->
                    file.name.startsWith("${uniqueThumbBase}_") &&
                        file.name != destFile.name && file.name != stableThumbFile.name
                }?.forEach {
                    try { it.delete() } catch (_: Exception) {}
                }

                // Clear thumbnail lookup cache for this track
                synchronized(thumbnailFileCache) {
                    thumbnailFileCache.evictAll()
                }

                if (bitmap != null) {
                    val compressedBytes = writeBitmapAtomically(destFile, bitmap, 92, songId)
                    writeBitmapAtomically(stableThumbFile, bitmap, 92, songId)
                    if (songPath.isNullOrBlank()) {
                        writeBitmapAtomically(legacyDestFile, bitmap, 92, songId)
                        writeBitmapAtomically(stableFile, bitmap, 92, songId)
                    }

                    if (extThumbnailDir != null) {
                        try {
                            writeBitmapAtomically(File(extThumbnailDir, "$uniqueThumbBase.jpg"), bitmap, 92, songId)
                        } catch (_: Exception) {}
                    }

                    if (!songPath.isNullOrBlank()) {
                        val pathFile = File(coversDir, "song_path_${pathHash}.jpg")
                        writeBitmapAtomically(pathFile, bitmap, 92, songId)

                        // Also attempt saving in local song folder's .thumbnail directory if permitted by scoped storage
                        try {
                            val parentDir = File(songPath).parentFile
                            if (parentDir != null && parentDir.exists() && parentDir.canWrite()) {
                                val localThumbDir = File(parentDir, ".thumbnail")
                                if (!localThumbDir.exists()) localThumbDir.mkdirs()
                                if (localThumbDir.exists() && localThumbDir.canWrite()) {
                                    try { File(localThumbDir, ".nomedia").createNewFile() } catch (_: Exception) {}
                                    writeBitmapAtomically(File(localThumbDir, "$uniqueThumbBase.jpg"), bitmap, 92, songId)
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    val pArtworksDir = File(context.filesDir, CACHE_DIR_NAME)
                    if (!pArtworksDir.exists()) pArtworksDir.mkdirs()
                    val trackFileName = if (songPath.isNullOrBlank()) "$songId.jpg" else ArtworkResolutionCache.trackFileName(songId, songPath)
                    val pArtFile = File(pArtworksDir, trackFileName)
                    writeBitmapAtomically(pArtFile, bitmap, 90, songId)

                    val pSongArtDir = File(context.filesDir, SONG_ARTWORKS_DIR_NAME)
                    if (!pSongArtDir.exists()) pSongArtDir.mkdirs()
                    val pSongArtFile = File(pSongArtDir, trackFileName)
                    writeBitmapAtomically(pSongArtFile, bitmap, 90, songId)

                    val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val cacheFile = File(cacheDir, trackFileName)
                    writeBitmapAtomically(cacheFile, bitmap, 90, songId)

                    val songArtDir = File(context.cacheDir, SONG_ARTWORKS_DIR_NAME)
                    if (!songArtDir.exists()) songArtDir.mkdirs()
                    val songArtFile = File(songArtDir, trackFileName)
                    writeBitmapAtomically(songArtFile, bitmap, 90, songId)

                    if (compressedBytes != null) {
                        putNotificationArtwork(songId, compressedBytes, bitmap, songPath)
                    } else {
                        putCachedNotificationBitmap(songId, bitmap, songPath)
                    }

                    try {
                        context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                            .edit().remove(songId.toString())
                            .remove(ArtworkResolutionCache.trackIdentity(songId, songPath))
                            .apply()
                    } catch (_: Exception) {}

                    val newUri = Uri.fromFile(destFile).toString()
                    ManualTagStore.markArtworkManuallyEdited(context, songId, songPath.orEmpty(), newUri)
                    ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, newUri, songPath, forceReplace = true)

                    try {
                        val loader = coil.Coil.imageLoader(context)
                        loader.memoryCache?.keys?.filter { it.key.contains("art_${songId}_") || it.key.contains("art_id:$songId") }?.forEach { key ->
                            loader.memoryCache?.remove(key)
                        }
                        // Pre-warm Coil memory cache with the new cover immediately
                        val cacheKey = "art_${songId}_${newUri.hashCode()}"
                        val warmReq = coil.request.ImageRequest.Builder(context)
                            .data(newUri)
                            .memoryCacheKey(cacheKey)
                            .diskCacheKey(cacheKey)
                            .allowHardware(true)
                            .build()
                        loader.enqueue(warmReq)
                    } catch (_: Exception) {}

                    ArtworkResolutionCache.notifyArtworkChanged(songId, newUri, songPath)
                    return@withContext newUri
                } else {
                    if (writeBytesAtomically(destFile, rawBytes, songId)) {
                        writeBytesAtomically(stableThumbFile, rawBytes, songId)
                        if (songPath.isNullOrBlank()) {
                            writeBytesAtomically(legacyDestFile, rawBytes, songId)
                            writeBytesAtomically(stableFile, rawBytes, songId)
                        }
                        val newUri = Uri.fromFile(destFile).toString()
                        ManualTagStore.markArtworkManuallyEdited(context, songId, songPath.orEmpty(), newUri)
                        ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, newUri, songPath, forceReplace = true)
                        ArtworkResolutionCache.notifyArtworkChanged(songId, newUri, songPath)
                        return@withContext newUri
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext uriString
    }

    suspend fun persistPlaylistCover(context: Context, playlistId: Long, uriString: String?): String? = withContext(Dispatchers.IO) {
        if (uriString.isNullOrBlank()) return@withContext null

        try {
            val coversDir = File(context.filesDir, "playlist_covers")
            if (!coversDir.exists()) coversDir.mkdirs()

            coversDir.listFiles { file -> file.name.startsWith("playlist_${playlistId}_") || file.name == "playlist_$playlistId.jpg" }?.forEach {
                try { it.delete() } catch (_: Exception) {}
            }

            val destFile = File(coversDir, "playlist_${playlistId}_${System.currentTimeMillis()}.jpg")
            val stableFile = File(coversDir, "playlist_$playlistId.jpg")

            val inputStream: java.io.InputStream? = if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
                val url = java.net.URL(uriString)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.inputStream
            } else if (uriString.startsWith("file://")) {
                val filePath = Uri.parse(uriString).path ?: ""
                val f = File(filePath)
                if (f.exists() && f.length() > 0) java.io.FileInputStream(f) else null
            } else {
                val inputUri = Uri.parse(uriString)
                context.contentResolver.openInputStream(inputUri)
            }

            inputStream?.use { input ->
                val rawBytes = input.readBytes()
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
                val sampleSize = calculateInSampleSize(boundsOpts, 600, 600)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                if (bitmap != null) {
                    writeBitmapAtomically(destFile, bitmap, 92, playlistId)
                    writeBitmapAtomically(stableFile, bitmap, 92, playlistId)

                    val newUri = Uri.fromFile(destFile).toString()
                    try {
                        val loader = coil.Coil.imageLoader(context)
                        loader.memoryCache?.keys?.filter { it.key.contains("pl_cover_${playlistId}_") }?.forEach { key ->
                            loader.memoryCache?.remove(key)
                        }
                        val preloadReq = coil.request.ImageRequest.Builder(context)
                            .data(newUri)
                            .memoryCacheKey("pl_cover_${playlistId}_${newUri.hashCode()}")
                            .diskCacheKey("pl_cover_${playlistId}_${newUri.hashCode()}")
                            .allowHardware(true)
                            .build()
                        loader.enqueue(preloadReq)
                    } catch (_: Exception) {}

                    return@withContext newUri
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext uriString
    }

    fun cleanStorage(context: Context) {
        try {
            val artworksDir = File(context.filesDir, CACHE_DIR_NAME)
            if (!artworksDir.exists()) artworksDir.mkdirs()

            // Remove any leftover .tmp files or truncated JPEG files
            artworksDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".tmp")) {
                    try { file.delete() } catch (_: Exception) {}
                } else {
                    isValidCompleteImageFile(file)
                }
            }

            val persistentSongArtDir = File(context.filesDir, SONG_ARTWORKS_DIR_NAME)
            if (persistentSongArtDir.exists()) {
                val files = persistentSongArtDir.listFiles() ?: emptyArray()
                for (file in files) {
                    val dest = File(artworksDir, file.name)
                    if (!isValidCompleteImageFile(dest) && isValidCompleteImageFile(file)) {
                        try { file.copyTo(dest, overwrite = true) } catch (_: Exception) {}
                    }
                    file.delete()
                }
                persistentSongArtDir.delete()
            }

            val crashDir = File(context.filesDir, "crash_reports")
            if (crashDir.exists()) {
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                crashDir.listFiles()?.forEach { if (it.lastModified() < weekAgo) it.delete() }
            }

            if (artworksDir.exists()) {
                val files = artworksDir.listFiles() ?: emptyArray()
                var totalBytes = files.sumOf { it.length() }
                val maxAllowedBytes = 150L * 1024 * 1024 // 150MB
                if (totalBytes > maxAllowedBytes) {
                    val sortedByAge = files.sortedBy { it.lastModified() }
                    for (f in sortedByAge) {
                        val len = f.length()
                        if (f.delete()) {
                            totalBytes -= len
                            if (totalBytes <= 100L * 1024 * 1024) break
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
