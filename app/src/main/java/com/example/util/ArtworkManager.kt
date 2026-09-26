package com.example.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.example.ui.components.ArtworkResolutionCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ArtworkManager {

    private const val CACHE_DIR_NAME = "artworks"
    private const val SONG_ARTWORKS_DIR_NAME = "song_artworks"

    private val notificationArtCache = android.util.LruCache<Long, ByteArray>(30)
    private val notificationBitmapCache = android.util.LruCache<Long, Bitmap>(30)

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

        val rawBytes = getOrExtractArtworkBytesSync(context, songId, filePath, albumId, artworkUriStr) ?: return null

        val finalBytes = try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            options.inSampleSize = calculateInSampleSize(options, 512, 512)
            options.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            if (bmp != null) {
                val stream = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                bmp.recycle()
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
            coil.Coil.imageLoader(context).memoryCache?.clear()
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

        // 2. Check persistent & cached artwork files
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

        // 4. Check same-folder artwork (cover.jpg, folder.jpg, album.jpg, etc.)
        if (filePath.isNotBlank()) {
            try {
                val parentDir = File(filePath).parentFile
                if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                    val coverFile = parentDir.listFiles { file ->
                        file.isFile && (
                            file.name.equals("cover.jpg", ignoreCase = true) ||
                            file.name.equals("cover.png", ignoreCase = true) ||
                            file.name.equals("folder.jpg", ignoreCase = true) ||
                            file.name.equals("album.jpg", ignoreCase = true) ||
                            file.name.equals("artwork.jpg", ignoreCase = true) ||
                            file.name.equals("front.jpg", ignoreCase = true)
                        )
                    }?.firstOrNull()
                    if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                        val bytes = coverFile.readBytes()
                        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        FileOutputStream(cacheFile).use { it.write(bytes) }
                        return bytes
                    }
                }
            } catch (_: Exception) {}
        }

        // 5. MediaStore album art
        if (albumId > 0) {
            try {
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                context.contentResolver.openInputStream(albumArtUri)?.use {
                    val bytes = it.readBytes()
                    if (bytes.isNotEmpty()) {
                        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        FileOutputStream(cacheFile).use { out -> out.write(bytes) }
                        return bytes
                    }
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int = 512, reqHeight: Int = 512): Int {
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

    suspend fun getOrExtractArtworkUri(
        context: Context,
        songId: Long,
        filePath: String,
        albumId: Long,
        albumName: String = ""
    ): String? = withContext(Dispatchers.IO) {
        // 0. Check manual song_covers first (highest priority)
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

        // 1. Check persistent storage first (never deleted by OS cache cleanup)
        val persistentDir = File(context.filesDir, CACHE_DIR_NAME)
        if (!persistentDir.exists()) persistentDir.mkdirs()
        val persistentFile = File(persistentDir, "$songId.jpg")
        if (persistentFile.exists() && persistentFile.length() > 0) {
            return@withContext Uri.fromFile(persistentFile).toString()
        }

        val persistentSongArtDir = File(context.filesDir, SONG_ARTWORKS_DIR_NAME)
        if (!persistentSongArtDir.exists()) persistentSongArtDir.mkdirs()
        val persistentSongArtFile = File(persistentSongArtDir, "$songId.jpg")
        if (persistentSongArtFile.exists() && persistentSongArtFile.length() > 0) {
            return@withContext Uri.fromFile(persistentSongArtFile).toString()
        }

        // Check cached artwork for this specific song ID
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val cacheFile = File(cacheDir, "$songId.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                cacheFile.copyTo(persistentFile, overwrite = true)
                return@withContext Uri.fromFile(persistentFile).toString()
            } catch (_: Exception) {
                return@withContext Uri.fromFile(cacheFile).toString()
            }
        }

        // Helper to save bitmap to canonical persistent storage (artworks directory)
        fun saveArtworkBitmap(bitmap: Bitmap): String {
            try {
                FileOutputStream(persistentFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
            } catch (_: Exception) {}
            return Uri.fromFile(persistentFile).toString()
        }

        // 2. Android 10+ Hardware Thumbnail extraction from MediaStore
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && songId > 0L) {
            try {
                val songContentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
                val thumb = context.contentResolver.loadThumbnail(songContentUri, android.util.Size(512, 512), null)
                if (thumb != null) {
                    val uriStr = saveArtworkBitmap(thumb)
                    thumb.recycle()
                    ArtworkResolutionCache.cache[songId] = uriStr
                    return@withContext uriStr
                }
            } catch (_: Exception) {}
        }

        // 3. Extract Embedded Picture from the actual audio file (content URI or file path)
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
                }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
        }

        // 4. Check same-folder artwork (cover.jpg, folder.jpg, album.jpg, etc.)
        try {
            if (filePath.isNotBlank()) {
                val parentDir = File(filePath).parentFile
                if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                    val coverFiles = parentDir.listFiles { file ->
                        file.isFile && (
                            file.name.equals("cover.jpg", ignoreCase = true) ||
                            file.name.equals("cover.png", ignoreCase = true) ||
                            file.name.equals("folder.jpg", ignoreCase = true) ||
                            file.name.equals("folder.png", ignoreCase = true) ||
                            file.name.equals("album.jpg", ignoreCase = true) ||
                            file.name.equals("album.png", ignoreCase = true) ||
                            file.name.equals("artwork.jpg", ignoreCase = true) ||
                            file.name.equals("front.jpg", ignoreCase = true)
                        )
                    }
                    if (!coverFiles.isNullOrEmpty()) {
                        val coverFile = coverFiles[0]
                        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(coverFile.absolutePath, boundsOpts)

                        val sampleSize = calculateInSampleSize(boundsOpts, 512, 512)
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val bitmap = BitmapFactory.decodeFile(coverFile.absolutePath, opts)
                        if (bitmap != null) {
                            val uriStr = saveArtworkBitmap(bitmap)
                            bitmap.recycle()
                            ArtworkResolutionCache.cache[songId] = uriStr
                            return@withContext uriStr
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 5. MediaStore album art URI if albumId > 0
        if (albumId > 0) {
            try {
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                // Verify the URI is actually readable
                context.contentResolver.openInputStream(albumArtUri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { 
                        inSampleSize = 2
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeStream(stream, null, opts)
                    if (bmp != null) {
                        val uriStr = saveArtworkBitmap(bmp)
                        bmp.recycle()
                        ArtworkResolutionCache.cache[songId] = uriStr
                        return@withContext uriStr
                    }
                }
            } catch (_: Exception) {}
        }

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
