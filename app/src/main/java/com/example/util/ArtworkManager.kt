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

    // Per-song synchronization locks to prevent concurrent threads from corrupting or truncating JPEG files
    private val songLocks = ConcurrentHashMap<Long, Any>()
    private fun lockForSong(songId: Long): Any = songLocks.computeIfAbsent(songId) { Any() }

    // Bounded semaphore for MediaMetadataRetriever so background preloads never starve visible UI artwork
    private val mmrSemaphore = Semaphore(6)

    private val notificationArtCache = object : android.util.LruCache<Long, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }
    private val notificationBitmapCache = object : android.util.LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    /**
     * Validates that a cached JPEG/PNG file on disk is complete and not a half-written truncated JPEG
     * (which causes Skia/Coil to render the top half and fill the bottom half with solid black/green).
     * Automatically deletes truncated/corrupted cache files so they are cleanly re-extracted.
     */
    fun isValidCompleteImageFile(file: File?): Boolean {
        if (file == null || !file.exists() || !file.isFile) return false
        val len = file.length()
        if (len < 256L) {
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

    fun getCachedNotificationArtwork(songId: Long): ByteArray? {
        synchronized(notificationArtCache) {
            return notificationArtCache.get(songId)
        }
    }

    fun getCachedNotificationBitmap(songId: Long): Bitmap? {
        synchronized(notificationBitmapCache) {
            val bmp = notificationBitmapCache.get(songId)
            return if (bmp != null && !bmp.isRecycled) bmp else null
        }
    }

    fun putCachedNotificationBitmap(songId: Long, bitmap: Bitmap) {
        if (songId <= 0L || bitmap.isRecycled) return
        synchronized(notificationBitmapCache) {
            notificationBitmapCache.put(songId, bitmap)
        }
    }

    fun invalidateNotificationArtwork(songId: Long) {
        synchronized(notificationArtCache) {
            notificationArtCache.remove(songId)
        }
        synchronized(notificationBitmapCache) {
            notificationBitmapCache.remove(songId)
        }
    }

    fun putCachedNotificationArtwork(songId: Long, bytes: ByteArray) {
        if (songId <= 0L || bytes.isEmpty()) return
        synchronized(notificationArtCache) {
            notificationArtCache.put(songId, bytes)
        }
    }

    fun putNotificationArtwork(songId: Long, bytes: ByteArray, bitmap: Bitmap) {
        if (songId <= 0L) return
        synchronized(notificationArtCache) {
            notificationArtCache.put(songId, bytes)
        }
        if (!bitmap.isRecycled) {
            synchronized(notificationBitmapCache) {
                notificationBitmapCache.put(songId, bitmap)
            }
        }
    }

    private val thumbnailFileCache = android.util.LruCache<String, String>(1000)

    /**
     * Finds image artwork from local device .thumbnail / .thumbnails files and companion cover files
     * associated with the song or its audio folder.
     */
    fun findThumbnailArtwork(filePath: String): File? {
        if (filePath.isBlank()) return null
        synchronized(thumbnailFileCache) {
            val cachedPath = thumbnailFileCache.get(filePath)
            if (cachedPath != null) {
                return if (cachedPath.isNotEmpty()) {
                    val f = File(cachedPath)
                    if (isValidCompleteImageFile(f)) f else null
                } else null
            }
        }

        return try {
            val audioFile = File(filePath)
            val parentDir = audioFile.parentFile ?: return null
            if (!parentDir.exists() || !parentDir.isDirectory) return null

            val baseName = audioFile.nameWithoutExtension

            // 1. Direct song-specific .thumbnail / companion image files in the same directory
            val candidateFileNames = listOf(
                "$baseName.jpg",
                "$baseName.png",
                "$baseName.jpeg",
                "$baseName.webp",
                "$baseName.cover.jpg",
                "$baseName.thumbnail",
                "$baseName.thumb",
                "$baseName.thumbnail.jpg",
                "$baseName.thumbnail.png",
                ".$baseName.thumbnail",
                ".$baseName.thumb",
                "cover.jpg",
                "cover.png",
                "folder.jpg",
                "album.jpg",
                "front.jpg"
            )

            var resolvedFile: File? = null
            for (cand in candidateFileNames) {
                val f = File(parentDir, cand)
                if (isValidCompleteImageFile(f)) {
                    resolvedFile = f
                    break
                }
            }

            // 2. Check within hidden .thumbnails / .thumbnail folder in parent directory
            if (resolvedFile == null) {
                val thumbDirs = listOf(
                    File(parentDir, ".thumbnails"),
                    File(parentDir, ".thumbnail"),
                    File(parentDir, "thumbnails"),
                    File(parentDir, "thumbnail")
                )

                for (td in thumbDirs) {
                    if (td.exists() && td.isDirectory) {
                        val matchingFile = td.listFiles { file ->
                            isValidCompleteImageFile(file) && (
                                file.nameWithoutExtension.equals(baseName, ignoreCase = true) ||
                                    file.nameWithoutExtension.startsWith("${baseName}_", ignoreCase = true) ||
                                    file.nameWithoutExtension.startsWith("${baseName}.", ignoreCase = true) ||
                                    file.name.equals("$baseName.jpg", ignoreCase = true) ||
                                    file.name.equals("$baseName.png", ignoreCase = true)
                                )
                        }?.firstOrNull()
                        if (matchingFile != null) {
                            resolvedFile = matchingFile
                            break
                        }
                    }
                }
            }

            synchronized(thumbnailFileCache) {
                thumbnailFileCache.put(filePath, resolvedFile?.absolutePath ?: "")
            }

            resolvedFile
        } catch (_: Exception) {
            synchronized(thumbnailFileCache) {
                thumbnailFileCache.put(filePath, "")
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
        synchronized(notificationArtCache) {
            val cached = notificationArtCache.get(songId)
            if (cached != null && cached.isNotEmpty()) return cached
        }

        val effectiveUriStr = if (!artworkUriStr.isNullOrBlank()) {
            artworkUriStr
        } else {
            ArtworkResolutionCache.getMemory(songId)
                ?: ArtworkResolutionCache.getOrCheckDisk(context, songId, filePath)
        }

        val rawBytes = getOrExtractArtworkBytesSync(context, songId, filePath, albumId, effectiveUriStr) ?: return null

        val finalBytes = try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            options.inSampleSize = calculateInSampleSize(options, 320, 320)
            options.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            if (bmp != null) {
                putCachedNotificationBitmap(songId, bmp)
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                stream.toByteArray()
            } else {
                rawBytes
            }
        } catch (_: Throwable) {
            rawBytes
        }

        synchronized(notificationArtCache) {
            notificationArtCache.put(songId, finalBytes)
        }
        return finalBytes
    }

    fun saveFetchedArtworkLocally(context: Context, songId: Long, url: String): String? {
        if (songId <= 0L || url.isBlank()) return null
        return try {
            val destDir = File(context.filesDir, CACHE_DIR_NAME)
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, "$songId.jpg")

            val rawBytes = if (url.startsWith("http://") || url.startsWith("https://")) {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.inputStream.use { it.readBytes() }
            } else if (url.startsWith("file://")) {
                val f = File(Uri.parse(url).path ?: "")
                if (isValidCompleteImageFile(f)) f.readBytes() else null
            } else null

            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
                if (bmp != null) {
                    val savedBytes = writeBitmapAtomically(destFile, bmp, 90, songId)
                    if (savedBytes != null) {
                        putNotificationArtwork(songId, savedBytes, bmp)
                        val localUri = Uri.fromFile(destFile).toString()
                        ArtworkResolutionCache.cache[songId] = localUri
                        com.example.service.PlaybackManager.activeInstance?.let { pm ->
                            if (pm.playbackState.value.currentSong?.id == songId) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    pm.updateCurrentSongArtwork(songId, localUri)
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
                    if (cacheFile.exists()) cacheFile.delete()
                    val songArtFile = File(File(context.cacheDir, SONG_ARTWORKS_DIR_NAME), "$songId.jpg")
                    if (songArtFile.exists()) songArtFile.delete()
                    val persistentFile = File(File(context.filesDir, CACHE_DIR_NAME), "$songId.jpg")
                    if (persistentFile.exists()) persistentFile.delete()
                    val persistentSongArt = File(File(context.filesDir, SONG_ARTWORKS_DIR_NAME), "$songId.jpg")
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
     * Fast MediaStore / ContentResolver thumbnail extraction fallback when embedded MMR fails or is missing.
     */
    private fun extractFastMediaStoreBitmap(context: Context, songId: Long, albumId: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (songId > 0L) {
                try {
                    val songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
                    val bmp = context.contentResolver.loadThumbnail(songUri, Size(512, 512), null)
                    if (bmp != null && bmp.width > 8 && bmp.height > 8) return bmp
                } catch (_: Throwable) {}
            }
            if (albumId > 0L) {
                try {
                    val albumUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                    val bmp = context.contentResolver.loadThumbnail(albumUri, Size(512, 512), null)
                    if (bmp != null && bmp.width > 8 && bmp.height > 8) return bmp
                } catch (_: Throwable) {}
            }
        }
        if (albumId > 0L) {
            try {
                val legacyAlbumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                context.contentResolver.openInputStream(legacyAlbumArtUri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.isNotEmpty()) {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        opts.inSampleSize = calculateInSampleSize(opts, 512, 512)
                        opts.inJustDecodeBounds = false
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (bmp != null) return bmp
                    }
                }
            } catch (_: Throwable) {}
        }
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
        if (filePath.isNotBlank()) {
            val thumbFile = findThumbnailArtwork(filePath)
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
                            putCachedNotificationBitmap(songId, decodedBmp)
                            return cleanJpeg ?: artBytes
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
        }

        // 5. Fast MediaStore thumbnail / albumart fallback
        val mediaStoreBmp = extractFastMediaStoreBitmap(context, songId, albumId)
        if (mediaStoreBmp != null) {
            val saved = writeBitmapAtomically(persistentFile, mediaStoreBmp, 90, songId)
            putCachedNotificationBitmap(songId, mediaStoreBmp)
            if (saved != null) return saved
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
            val mem = ArtworkResolutionCache.getMemory(songId)
            if (!mem.isNullOrBlank()) return@withContext mem
        }

        // 1. Check manual song_covers first (highest priority, user-explicit)
        val manualMetaArt = ManualTagStore.getManualMetadata(context, songId, filePath)?.artworkUri
        if (!manualMetaArt.isNullOrBlank()) {
            if (songId > 0L) ArtworkResolutionCache.cache[songId] = manualMetaArt
            return@withContext manualMetaArt
        }

        val manualCoverUri = ArtworkResolutionCache.getManualCoverUri(context, songId, filePath)
        if (!manualCoverUri.isNullOrBlank()) {
            if (songId > 0L) ArtworkResolutionCache.cache[songId] = manualCoverUri
            return@withContext manualCoverUri
        }

        val persistentDir = File(context.filesDir, CACHE_DIR_NAME)
        if (!persistentDir.exists()) persistentDir.mkdirs()
        val persistentFile = File(persistentDir, "$songId.jpg")

        // 2. Fast-path: If already extracted & verified valid in persistent storage, return immediately!
        if (songId > 0L && isValidCompleteImageFile(persistentFile)) {
            val uriStr = Uri.fromFile(persistentFile).toString()
            ArtworkResolutionCache.cache[songId] = uriStr
            return@withContext uriStr
        }

        // 3. Extract with bounded concurrency to prevent IO starvation during fast scroll
        mmrSemaphore.withPermit {
            // Re-check after acquiring permit in case another coroutine just extracted it
            if (songId > 0L && isValidCompleteImageFile(persistentFile)) {
                val uriStr = Uri.fromFile(persistentFile).toString()
                ArtworkResolutionCache.cache[songId] = uriStr
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
                                    putNotificationArtwork(songId, savedBytes, bitmap)
                                    val uriStr = Uri.fromFile(persistentFile).toString()
                                    ArtworkResolutionCache.cache[songId] = uriStr
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
                                putNotificationArtwork(songId, savedBytes, bitmap)
                                val uriStr = Uri.fromFile(persistentFile).toString()
                                ArtworkResolutionCache.cache[songId] = uriStr
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
                    putNotificationArtwork(songId, savedBytes, mediaStoreBmp)
                    val uriStr = Uri.fromFile(persistentFile).toString()
                    ArtworkResolutionCache.cache[songId] = uriStr
                    return@withPermit uriStr
                }
            }

            // 3C. Song-specific local companion file (e.g. "SongTitle.jpg", "cover.jpg")
            if (filePath.isNotBlank()) {
                val thumbFile = findThumbnailArtwork(filePath)
                if (thumbFile != null && isValidCompleteImageFile(thumbFile)) {
                    val thumbUri = Uri.fromFile(thumbFile).toString()
                    if (songId > 0L) ArtworkResolutionCache.cache[songId] = thumbUri
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

            val timestamp = System.currentTimeMillis()
            val destFile = File(coversDir, "song_${songId}_$timestamp.jpg")
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
                coversDir.listFiles { file ->
                    file.name.startsWith("song_${songId}_") && file.name != destFile.name
                }?.forEach {
                    try { it.delete() } catch (_: Exception) {}
                }

                if (bitmap != null) {
                    val compressedBytes = writeBitmapAtomically(destFile, bitmap, 92, songId)
                    writeBitmapAtomically(stableFile, bitmap, 92, songId)

                    if (!songPath.isNullOrBlank()) {
                        val pathHash = songPath.trim().lowercase().hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL
                        val pathFile = File(coversDir, "song_path_${pathHash}.jpg")
                        writeBitmapAtomically(pathFile, bitmap, 92, songId)
                    }

                    val pArtworksDir = File(context.filesDir, CACHE_DIR_NAME)
                    if (!pArtworksDir.exists()) pArtworksDir.mkdirs()
                    val pArtFile = File(pArtworksDir, "$songId.jpg")
                    writeBitmapAtomically(pArtFile, bitmap, 90, songId)

                    val pSongArtDir = File(context.filesDir, SONG_ARTWORKS_DIR_NAME)
                    if (!pSongArtDir.exists()) pSongArtDir.mkdirs()
                    val pSongArtFile = File(pSongArtDir, "$songId.jpg")
                    writeBitmapAtomically(pSongArtFile, bitmap, 90, songId)

                    val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val cacheFile = File(cacheDir, "$songId.jpg")
                    writeBitmapAtomically(cacheFile, bitmap, 90, songId)

                    val songArtDir = File(context.cacheDir, SONG_ARTWORKS_DIR_NAME)
                    if (!songArtDir.exists()) songArtDir.mkdirs()
                    val songArtFile = File(songArtDir, "$songId.jpg")
                    writeBitmapAtomically(songArtFile, bitmap, 90, songId)

                    if (compressedBytes != null) {
                        putNotificationArtwork(songId, compressedBytes, bitmap)
                    } else {
                        putCachedNotificationBitmap(songId, bitmap)
                    }

                    try {
                        context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                            .edit().remove(songId.toString()).apply()
                    } catch (_: Exception) {}

                    val newUri = Uri.fromFile(destFile).toString()

                    try {
                        val loader = coil.Coil.imageLoader(context)
                        loader.memoryCache?.keys?.filter { it.key.contains("art_${songId}_") }?.forEach { key ->
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

                    ArtworkResolutionCache.notifyArtworkChanged(songId, newUri)
                    return@withContext newUri
                } else {
                    if (writeBytesAtomically(destFile, rawBytes, songId)) {
                        writeBytesAtomically(stableFile, rawBytes, songId)
                        val newUri = Uri.fromFile(destFile).toString()
                        ArtworkResolutionCache.notifyArtworkChanged(songId, newUri)
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