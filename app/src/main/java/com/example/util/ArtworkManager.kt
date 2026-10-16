package com.example.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.example.ui.components.ArtworkResolutionCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ArtworkManager {

    private const val CACHE_DIR_NAME = "artworks"
    private const val SONG_ARTWORKS_DIR_NAME = "song_artworks"

    private val notificationArtCache = object : android.util.LruCache<Long, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }
    private val notificationBitmapCache = object : android.util.LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
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
            return notificationBitmapCache.get(songId)
        }
    }

    fun putCachedNotificationBitmap(songId: Long, bitmap: Bitmap) {
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
        synchronized(notificationArtCache) {
            notificationArtCache.put(songId, bytes)
        }
    }

    fun putNotificationArtwork(songId: Long, bytes: ByteArray, bitmap: Bitmap) {
        synchronized(notificationArtCache) {
            notificationArtCache.put(songId, bytes)
        }
        synchronized(notificationBitmapCache) {
            notificationBitmapCache.put(songId, bitmap)
        }
    }

    private val thumbnailFileCache = android.util.LruCache<String, String>(300)

    /**
     * Finds image artwork from local device .thumbnail / .thumbnails files and directories
     * associated with the song or its audio folder.
     */
    fun findThumbnailArtwork(filePath: String): File? {
        if (filePath.isBlank()) return null
        synchronized(thumbnailFileCache) {
            val cachedPath = thumbnailFileCache.get(filePath)
            if (cachedPath != null) {
                return if (cachedPath.isNotEmpty()) {
                    val f = File(cachedPath)
                    if (f.exists() && f.isFile && f.length() > 0L) f else null
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
                ".$baseName.thumb"
            )

            var resolvedFile: File? = null
            for (cand in candidateFileNames) {
                val f = File(parentDir, cand)
                if (f.exists() && f.isFile && f.length() > 0L) {
                    resolvedFile = f
                    break
                }
            }

            // 2. Check within hidden .thumbnails / .thumbnail folder in parent or directory
            // CRITICAL: Strictly match the song's baseName so we NEVER map a random song's artwork to an unrelated song!
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
                            file.isFile && file.length() > 0L && (
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
                    if (p != null && File(p).exists()) java.io.FileInputStream(p) else null
                }
                uriString.startsWith("/") -> {
                    val f = File(uriString)
                    if (f.exists()) java.io.FileInputStream(f) else null
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
                    if (f.exists()) java.io.FileInputStream(f) else context.contentResolver.openInputStream(Uri.parse(uriString))
                }
            }
            stream?.use { input ->
                val bytes = input.readBytes()
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
            if (cached != null) return cached
        }

        // Check authoritative memory resolution cache if uriStr not provided
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
            options.inSampleSize = calculateInSampleSize(options, 256, 256)
            options.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            if (bmp != null) {
                putCachedNotificationBitmap(songId, bmp)
                val stream = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
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
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.inputStream.use { it.readBytes() }
            } else if (url.startsWith("file://")) {
                val f = File(Uri.parse(url).path ?: "")
                if (f.exists() && f.length() > 0) f.readBytes() else null
            } else null

            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
                if (bmp != null) {
                    FileOutputStream(destFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    putNotificationArtwork(songId, rawBytes, bmp)
                    val localUri = Uri.fromFile(destFile).toString()
                    ArtworkResolutionCache.cache[songId] = localUri
                    com.example.service.PlaybackManager.activeInstance?.let { pm ->
                        if (pm.playbackState.value.currentSong?.id == songId) {
                            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                pm.updateCurrentSongArtwork(songId, localUri)
                            }
                        }
                    }
                    localUri
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
                val cacheFile = File(File(context.cacheDir, CACHE_DIR_NAME), "$songId.jpg")
                if (cacheFile.exists()) cacheFile.delete()
                val songArtFile = File(File(context.cacheDir, SONG_ARTWORKS_DIR_NAME), "$songId.jpg")
                if (songArtFile.exists()) songArtFile.delete()
                val persistentFile = File(File(context.filesDir, CACHE_DIR_NAME), "$songId.jpg")
                if (persistentFile.exists()) persistentFile.delete()
                val persistentSongArt = File(File(context.filesDir, SONG_ARTWORKS_DIR_NAME), "$songId.jpg")
                if (persistentSongArt.exists()) persistentSongArt.delete()
            }
            try {
                val loader = coil.Coil.imageLoader(context)
                loader.memoryCache?.keys?.filter { it.key.contains("art_${songId}_") }?.forEach { key ->
                    loader.memoryCache?.remove(key)
                }
            } catch (_: Throwable) {}
        } catch (_: Exception) {}
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
                file.name.startsWith("song_${songId}_") || file.name == "song_$songId.jpg" 
            }?.maxByOrNull { it.lastModified() }
            if (manualCover != null && manualCover.exists() && manualCover.length() > 0) {
                try { return manualCover.readBytes() } catch (_: Exception) {}
            }
        }

        // 1. If artworkUriStr is given and points to a file or content
        if (!artworkUriStr.isNullOrBlank()) {
            try {
                if (artworkUriStr.startsWith("file://")) {
                    val f = File(Uri.parse(artworkUriStr).path ?: "")
                    if (f.exists() && f.length() > 0) {
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

        // 2. Check local device .thumbnail file from user's storage
        if (filePath.isNotBlank()) {
            val thumbFile = findThumbnailArtwork(filePath)
            if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0L) {
                try {
                    val bytes = thumbFile.readBytes()
                    if (bytes.isNotEmpty()) {
                        val persistentDir = File(context.filesDir, CACHE_DIR_NAME)
                        if (!persistentDir.exists()) persistentDir.mkdirs()
                        val persistentFile = File(persistentDir, "$songId.jpg")
                        try { FileOutputStream(persistentFile).use { it.write(bytes) } } catch (_: Exception) {}
                        return bytes
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Check persistent & cached artwork files
        val persistentFile = File(File(context.filesDir, CACHE_DIR_NAME), "$songId.jpg")
        if (persistentFile.exists() && persistentFile.length() > 0) {
            try { return persistentFile.readBytes() } catch (_: Exception) {}
        }

        val persistentSongArt = File(File(context.filesDir, SONG_ARTWORKS_DIR_NAME), "$songId.jpg")
        if (persistentSongArt.exists() && persistentSongArt.length() > 0) {
            try { return persistentSongArt.readBytes() } catch (_: Exception) {}
        }

        val cacheFile = File(File(context.cacheDir, CACHE_DIR_NAME), "$songId.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val bytes = cacheFile.readBytes()
                val persistentDir = File(context.filesDir, CACHE_DIR_NAME)
                if (!persistentDir.exists()) persistentDir.mkdirs()
                FileOutputStream(persistentFile).use { it.write(bytes) }
                return bytes
            } catch (_: Exception) {}
        }

        // 3. Android 10+ Hardware Thumbnail extraction from MediaStore
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && songId > 0L) {
            try {
                val songContentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
                val thumb = context.contentResolver.loadThumbnail(songContentUri, android.util.Size(512, 512), null)
                if (thumb != null) {
                    val stream = java.io.ByteArrayOutputStream()
                    thumb.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    val bytes = stream.toByteArray()
                    thumb.recycle()
                    if (bytes.isNotEmpty()) {
                        val persistentDir = File(context.filesDir, CACHE_DIR_NAME)
                        if (!persistentDir.exists()) persistentDir.mkdirs()
                        FileOutputStream(persistentFile).use { it.write(bytes) }
                        return bytes
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Extract Embedded Picture from actual audio file via MediaMetadataRetriever (using content URI and file path)
        if (songId > 0L || filePath.isNotBlank()) {
            val mmr = MediaMetadataRetriever()
            try {
                var setSuccess = false
                if (songId > 0L) {
                    try {
                        val songContentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
                        mmr.setDataSource(context, songContentUri)
                        setSuccess = true
                    } catch (_: Exception) {}
                }
                if (!setSuccess && filePath.isNotBlank()) {
                    val f = File(filePath)
                    if (f.exists()) {
                        mmr.setDataSource(filePath)
                        setSuccess = true
                    }
                }
                if (setSuccess) {
                    val artBytes = mmr.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        val persistentDir = File(context.filesDir, CACHE_DIR_NAME)
                        if (!persistentDir.exists()) persistentDir.mkdirs()
                        try {
                            FileOutputStream(persistentFile).use { it.write(artBytes) }
                        } catch (_: Exception) {}
                        return artBytes
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
        }

        // 4. Check track-specific companion artwork (e.g. SongName.jpg, SongName.png)
        if (filePath.isNotBlank()) {
            val thumb = findThumbnailArtwork(filePath)
            if (thumb != null && thumb.exists() && thumb.length() > 0) {
                try {
                    val bytes = thumb.readBytes()
                    val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    FileOutputStream(cacheFile).use { it.write(bytes) }
                    return bytes
                } catch (_: Exception) {}
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

    /**
     * Purges false mass-duplicated artwork cache files (e.g. where MediaStore or folder art
     * mistakenly assigned the same movie poster or album image to dozens of different songs).
     * Any artwork in song_covers/ is manual user art and is strictly preserved.
     */
    fun purgePollutedArtworkCache(context: Context) {
        try {
            // Clear any polluted online artwork cache from shared preferences
            try {
                context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE).edit().clear().apply()
                context.getSharedPreferences("song_artworks_online_cache", Context.MODE_PRIVATE).edit().clear().apply()
            } catch (_: Exception) {}

            val coversDir = File(context.filesDir, "song_covers")
            val manualSongIds = mutableSetOf<Long>()
            if (coversDir.exists() && coversDir.isDirectory) {
                coversDir.listFiles()?.forEach { cf ->
                    if (cf.name.startsWith("song_") && cf.name.endsWith(".jpg")) {
                        val idPart = cf.name.removePrefix("song_").substringBefore("_").substringBefore(".jpg")
                        idPart.toLongOrNull()?.let { manualSongIds.add(it) }
                    }
                }
            }

            val artworksDir = File(context.filesDir, CACHE_DIR_NAME)
            val purgedSongIds = mutableSetOf<Long>()
            if (artworksDir.exists() && artworksDir.isDirectory) {
                val files = artworksDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                if (files != null && files.isNotEmpty()) {
                    val groupedByLength = files.groupBy { it.length() }.filter { it.key > 0L && it.value.size >= 2 }
                    for ((_, dupeFiles) in groupedByLength) {
                        for (file in dupeFiles) {
                            val id = file.nameWithoutExtension.toLongOrNull() ?: continue
                            if (!manualSongIds.contains(id)) {
                                file.delete()
                                purgedSongIds.add(id)
                                ArtworkResolutionCache.cache.remove(id)
                            }
                        }
                    }
                }
            }

            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
            if (cacheDir.exists() && cacheDir.isDirectory) {
                val cFiles = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                if (cFiles != null) {
                    val grouped = cFiles.groupBy { it.length() }.filter { it.key > 0L && it.value.size >= 2 }
                    for ((_, dupeFiles) in grouped) {
                        for (f in dupeFiles) {
                            f.delete()
                        }
                    }
                }
            }

            // Also clean up any Room DB entries that were pointing to false duplicate artworks or deleted cache files
            if (purgedSongIds.isNotEmpty()) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = com.example.data.local.AppDatabase.getInstance(context)
                        for (id in purgedSongIds) {
                            val song = db.songDao().getSongById(id)
                            if (song != null && !song.isManuallyEdited) {
                                db.songDao().updateSongArtwork(id, null)
                            }
                        }
                        ArtworkResolutionCache.clear()
                    } catch (_: Throwable) {}
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
        // 0. Check manual song_covers first (highest priority, user-explicit)
        val manualMetaArt = com.example.util.ManualTagStore.getManualMetadata(context, songId, filePath)?.artworkUri
        if (!manualMetaArt.isNullOrBlank()) {
            ArtworkResolutionCache.cache[songId] = manualMetaArt
            return@withContext manualMetaArt
        }

        val coversDir = File(context.filesDir, "song_covers")
        if (coversDir.exists()) {
            val manualCover = coversDir.listFiles { file -> 
                file.name.startsWith("song_${songId}_") || file.name == "song_$songId.jpg" 
            }?.maxByOrNull { it.lastModified() }
            if (manualCover != null && manualCover.exists() && manualCover.length() > 0) {
                val uriStr = Uri.fromFile(manualCover).toString()
                ArtworkResolutionCache.cache[songId] = uriStr
                return@withContext uriStr
            }
        }

        val persistentDir = File(context.filesDir, CACHE_DIR_NAME)
        if (!persistentDir.exists()) persistentDir.mkdirs()
        val persistentFile = File(persistentDir, "$songId.jpg")

        // Helper to save bitmap to canonical persistent storage (artworks directory)
        fun saveArtworkBitmap(bitmap: Bitmap): String {
            try {
                FileOutputStream(persistentFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
            } catch (_: Exception) {}
            return Uri.fromFile(persistentFile).toString()
        }

        // 1. EMBEDDED PICTURE FROM THE ACTUAL AUDIO FILE (Highest Local Priority)
        // Extract embedded ID3/FLAC/M4A picture directly from the song's audio file.
        // This is 100% unique to this track and prevents shared MediaStore album thumbnails from poisoning tracks.
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
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)
                        if (bitmap != null) {
                            val uriStr = saveArtworkBitmap(bitmap)
                            bitmap.recycle()
                            ArtworkResolutionCache.cache[songId] = uriStr
                            return@withContext uriStr
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { mmr.release() } catch (_: Throwable) {}
                }
            }
        }

        if (songId > 0L) {
            // Also try ContentUri with MediaMetadataRetriever if file path extraction didn't find embedded art
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
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)
                    if (bitmap != null) {
                        val uriStr = saveArtworkBitmap(bitmap)
                        bitmap.recycle()
                        ArtworkResolutionCache.cache[songId] = uriStr
                        return@withContext uriStr
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
        }

        // 2. Song-specific local companion file (e.g. "SongTitle.jpg", "SongTitle.cover.jpg")
        if (filePath.isNotBlank()) {
            val thumbFile = findThumbnailArtwork(filePath)
            if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0L) {
                val thumbUri = Uri.fromFile(thumbFile).toString()
                ArtworkResolutionCache.cache[songId] = thumbUri
                return@withContext thumbUri
            }
        }

        // 3. If neither embedded art nor track-specific companion file exists, return null.
        // We strictly DO NOT call MediaStore loadThumbnail or audio/albumart, because Android
        // MediaStore groups unrelated tracks under generic album IDs and returns false artwork (e.g. movie posters).
        // Returning null allows OnlineArtistImageFetcher to search for this exact track online or render procedural art.

        // 4. Return null so OnlineArtistImageFetcher searches for this song's specific artwork online,
        // or displays the elegant gradient procedural artwork with the song title.
        // Under NO circumstances do we assign an unrelated song's cover!
        return@withContext null
    }

    suspend fun persistSongCover(context: Context, songId: Long, uriString: String?, songPath: String? = null): String? = withContext(Dispatchers.IO) {
        if (uriString.isNullOrBlank()) return@withContext null

        try {
            val coversDir = File(context.filesDir, "song_covers")
            if (!coversDir.exists()) coversDir.mkdirs()

            // Delete any existing cover files for this song to avoid stale versions
            coversDir.listFiles { file -> file.name.startsWith("song_${songId}_") || file.name == "song_$songId.jpg" }?.forEach {
                it.delete()
            }

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
            } catch (e: Exception) {
                null
            }

            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)

                if (bitmap != null) {
                    try {
                        FileOutputStream(destFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        }
                    } catch (_: Exception) {}

                    try {
                        FileOutputStream(stableFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        }
                    } catch (_: Exception) {}

                    if (!songPath.isNullOrBlank()) {
                        val pathHash = songPath.trim().lowercase().hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL
                        val pathFile = File(coversDir, "song_path_${pathHash}.jpg")
                        try {
                            FileOutputStream(pathFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                            }
                        } catch (_: Exception) {}
                    }

                    // Overwrite persistent storage files in filesDir/artworks and filesDir/song_artworks
                    val pArtworksDir = File(context.filesDir, CACHE_DIR_NAME)
                    if (!pArtworksDir.exists()) pArtworksDir.mkdirs()
                    val pArtFile = File(pArtworksDir, "$songId.jpg")
                    try {
                        FileOutputStream(pArtFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    } catch (_: Exception) {}

                    val pSongArtDir = File(context.filesDir, SONG_ARTWORKS_DIR_NAME)
                    if (!pSongArtDir.exists()) pSongArtDir.mkdirs()
                    val pSongArtFile = File(pSongArtDir, "$songId.jpg")
                    try {
                        FileOutputStream(pSongArtFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    } catch (_: Exception) {}

                    // Overwrite cache files in artworks and song_artworks
                    val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val cacheFile = File(cacheDir, "$songId.jpg")
                    try {
                        FileOutputStream(cacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    } catch (_: Exception) {}

                    val songArtDir = File(context.cacheDir, SONG_ARTWORKS_DIR_NAME)
                    if (!songArtDir.exists()) songArtDir.mkdirs()
                    val songArtFile = File(songArtDir, "$songId.jpg")
                    try {
                        FileOutputStream(songArtFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    } catch (_: Exception) {}

                    bitmap.recycle()

                    // Clear online artwork cache so it doesn't overwrite manual cover
                    try {
                        context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                            .edit().remove(songId.toString()).apply()
                    } catch (_: Exception) {}

                    // Clear Coil memory cache so new artwork is loaded
                    try { coil.Coil.imageLoader(context).memoryCache?.clear() } catch (_: Exception) {}

                    val newUri = Uri.fromFile(destFile).toString()
                    ArtworkResolutionCache.cache[songId] = newUri
                    return@withContext newUri
                } else {
                    // If bitmap decoding failed (e.g. SVG or raw binary), save raw bytes directly
                    try {
                        FileOutputStream(destFile).use { it.write(rawBytes) }
                        FileOutputStream(stableFile).use { it.write(rawBytes) }
                        val newUri = Uri.fromFile(destFile).toString()
                        ArtworkResolutionCache.cache[songId] = newUri
                        return@withContext newUri
                    } catch (_: Exception) {}
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

            // Delete any existing cover files for this playlist to avoid stale versions
            coversDir.listFiles { file -> file.name.startsWith("playlist_${playlistId}_") || file.name == "playlist_$playlistId.jpg" }?.forEach {
                try { it.delete() } catch (_: Exception) {}
            }

            val destFile = File(coversDir, "playlist_${playlistId}_${System.currentTimeMillis()}.jpg")

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
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeStream(input, null, opts)
                if (bitmap != null) {
                    FileOutputStream(destFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    bitmap.recycle()

                    try {
                        coil.Coil.imageLoader(context).memoryCache?.clear()
                    } catch (_: Exception) {}

                    return@withContext Uri.fromFile(destFile).toString()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext uriString
    }

    /**
     * Cleans up redundant duplicate artwork directories, stale crash logs,
     * and enforces a bounded size quota on auto-extracted artworks.
     * Note: Manual user-selected covers in song_covers are permanently protected.
     */
    fun cleanStorage(context: Context) {
        try {
            val artworksDir = File(context.filesDir, CACHE_DIR_NAME)
            if (!artworksDir.exists()) artworksDir.mkdirs()

            // 1. Migrate any unique files from redundant persistent song_artworks to artworks, then clean song_artworks
            val persistentSongArtDir = File(context.filesDir, SONG_ARTWORKS_DIR_NAME)
            if (persistentSongArtDir.exists()) {
                val files = persistentSongArtDir.listFiles() ?: emptyArray()
                for (file in files) {
                    val dest = File(artworksDir, file.name)
                    if (!dest.exists() || dest.length() == 0L) {
                        try { file.copyTo(dest, overwrite = true) } catch (_: Exception) {}
                    }
                    file.delete()
                }
                persistentSongArtDir.delete()
            }

            // 2. Clean temporary cache directories that duplicate artworks
            val cacheArtworksDir = File(context.cacheDir, CACHE_DIR_NAME)
            if (cacheArtworksDir.exists()) {
                cacheArtworksDir.deleteRecursively()
            }
            val cacheSongArtDir = File(context.cacheDir, SONG_ARTWORKS_DIR_NAME)
            if (cacheSongArtDir.exists()) {
                cacheSongArtDir.deleteRecursively()
            }

            // 3. Clean old crash logs (> 7 days)
            val crashDir = File(context.filesDir, "crash_reports")
            if (crashDir.exists()) {
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                crashDir.listFiles()?.forEach { if (it.lastModified() < weekAgo) it.delete() }
            }

            // 4. Enforce quota on auto-extracted artworks (max 60MB, keep newest)
            // (Note: manual covers in song_covers are NEVER touched or pruned)
            if (artworksDir.exists()) {
                val files = artworksDir.listFiles() ?: emptyArray()
                var totalBytes = files.sumOf { it.length() }
                val maxAllowedBytes = 60L * 1024 * 1024 // 60MB
                if (totalBytes > maxAllowedBytes) {
                    val sortedByAge = files.sortedBy { it.lastModified() }
                    for (f in sortedByAge) {
                        val len = f.length()
                        if (f.delete()) {
                            totalBytes -= len
                            if (totalBytes <= 40L * 1024 * 1024) break
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
