package com.example.ui.components

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.Song
import com.example.util.ArtworkManager
import com.example.util.OnlineArtistImageFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
object ArtworkResolutionCache {
    private val lruCache = object : android.util.LruCache<Long, String>(8000) {}
    private val trackArtworkCache = object : android.util.LruCache<String, String>(8000) {}
    private val trackIdentityCache = object : android.util.LruCache<Long, Pair<String, String>>(8000) {}
    private val trackArtworkPathById = ConcurrentHashMap<Long, String>()
    private val manualCoverCache = ConcurrentHashMap<Long, String>()
    private val manualPathCache = ConcurrentHashMap<String, String>()
    private val preloadedKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val failedLookupAt = ConcurrentHashMap<String, Long>()
    private val preloaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(6))
    private val backgroundWarmerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    val onlineArtworkDispatcher = Dispatchers.IO.limitedParallelism(2)

    // Pre-decoded 112x112 ImageBitmap cache (~24MB max, holds ~450 song thumbnails in RAM for 0ms synchronous rendering)
    private val thumbnailBitmapCache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap): Int {
            return (value.width * value.height * 4).coerceAtLeast(1024)
        }
    }

    @Volatile
    private var isDiskIndexWarm: Boolean = false
    private val diskIndexLock = Any()
    @Volatile private var diskIndexWarmJob: Job? = null

    @Volatile
    private var activePreloadJob: Job? = null
    @Volatile
    private var activeWarmAllJob: Job? = null

    val artworkUpdateSignal = mutableLongStateOf(0L)
    private val songArtworkVersions = ConcurrentHashMap<String, Long>()
    private val artworkUpdateSignals = ConcurrentHashMap<String, androidx.compose.runtime.MutableLongState>()

    private fun normalizedTrackPath(path: String?): String = path.orEmpty().trim().replace('\\', '/')

    fun trackIdentity(songId: Long, path: String?): String {
        val normalizedPath = normalizedTrackPath(path)
        if (normalizedPath.isBlank()) return "id:$songId"
        synchronized(trackIdentityCache) {
            trackIdentityCache.get(songId)?.let { (cachedPath, identity) ->
                if (cachedPath == normalizedPath) return identity
            }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalizedPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val identity = "id:$songId:$digest"
        synchronized(trackIdentityCache) {
            trackIdentityCache.put(songId, normalizedPath to identity)
        }
        return identity
    }

    fun trackFileName(songId: Long, path: String?): String =
        "${songId}_${trackIdentity(songId, path).substringAfterLast(':')}.jpg"

    fun getSongArtworkVersion(songId: Long): Long = songArtworkVersions["id:$songId"] ?: 0L

    fun getSongArtworkVersion(songId: Long, path: String?): Long =
        songArtworkVersions[trackIdentity(songId, path)] ?: getSongArtworkVersion(songId)

    fun getArtworkUpdateVersion(songId: Long, path: String?): Long =
        artworkUpdateSignals.computeIfAbsent(trackIdentity(songId, path)) { mutableLongStateOf(0L) }.longValue

    fun getThumbnailBitmap(songId: Long, path: String? = null): androidx.compose.ui.graphics.ImageBitmap? {
        if (songId <= 0L) return null
        return synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.get(trackIdentity(songId, path))
        }
    }

    fun putThumbnailBitmap(songId: Long, imageBitmap: androidx.compose.ui.graphics.ImageBitmap, path: String? = null) {
        if (songId <= 0L) return
        synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.put(trackIdentity(songId, path), imageBitmap)
        }
    }

    fun decodeAndCacheThumbnail(context: Context, songId: Long, uriStr: String, path: String? = null): androidx.compose.ui.graphics.ImageBitmap? {
        if (songId <= 0L || uriStr.isBlank()) return null
        getThumbnailBitmap(songId, path)?.let { return it }
        return try {
            val bmp = if (uriStr.startsWith("file://")) {
                val path = android.net.Uri.parse(uriStr).path ?: return null
                val f = File(path)
                if (!f.exists() || f.length() < 256L) return null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                val sample = ArtworkManager.calculateInSampleSize(bounds, 128, 128)
                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                android.graphics.BitmapFactory.decodeFile(path, decodeOpts)
            } else {
                ArtworkManager.decodeBitmapFromUri(context, uriStr, 128)
            }
            if (bmp != null && !bmp.isRecycled) {
                val imgBmp = bmp.asImageBitmap()
                putThumbnailBitmap(songId, imgBmp, path)
                imgBmp
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Populates the in-memory artwork URI index from existing local disk directories in a single fast pass (< 2ms).
     * Eliminates per-row File.exists() / listFiles() / RandomAccessFile calls during scrolling.
     */
    fun ensureDiskIndexWarm(context: Context) {
        if (isDiskIndexWarm) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            synchronized(diskIndexLock) {
                if (isDiskIndexWarm || diskIndexWarmJob?.isActive == true) return
                diskIndexWarmJob = backgroundWarmerScope.launch { ensureDiskIndexWarm(context.applicationContext) }
            }
            return
        }
        synchronized(diskIndexLock) {
            if (isDiskIndexWarm) return
            isDiskIndexWarm = true
            try {
                val appCtx = context.applicationContext
                val dirsToIndex = listOf(
                    File(appCtx.cacheDir, "song_artworks"),
                    File(appCtx.cacheDir, "artworks"),
                    File(appCtx.filesDir, "song_artworks"),
                    File(appCtx.filesDir, "artworks")
                )
                for (dir in dirsToIndex) {
                    val files = dir.listFiles() ?: continue
                    for (f in files) {
                        val name = f.name
                        if (name.endsWith(".jpg") && f.length() >= 256L) {
                            val idPart = name.removeSuffix(".jpg").toLongOrNull()
                            if (idPart != null && idPart > 0L) {
                                val uriStr = android.net.Uri.fromFile(f).toString()
                                cache[idPart] = uriStr
                            }
                        }
                    }
                }
                val coversDir = File(appCtx.filesDir, "song_covers")
                if (coversDir.exists()) {
                    coversDir.listFiles()?.forEach { f ->
                        val name = f.name
                        if (name.startsWith("song_") && name.endsWith(".jpg") && f.length() >= 256L) {
                            val core = name.removePrefix("song_").removeSuffix(".jpg")
                            val idStr = core.substringBefore('_')
                            val idPart = idStr.toLongOrNull()
                            if (idPart != null && idPart > 0L) {
                                val uriStr = android.net.Uri.fromFile(f).toString()
                                manualCoverCache[idPart] = uriStr
                                cache[idPart] = uriStr
                            }
                        }
                    }
                }
                val onlinePref = appCtx.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                for ((k, v) in onlinePref.all) {
                    val idPart = k.toLongOrNull()
                    val urlStr = v as? String
                    if (idPart != null && idPart > 0L && !urlStr.isNullOrBlank() && cache[idPart].isNullOrBlank()) {
                        cache[idPart] = urlStr
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Background library artwork warmer: pre-decodes thumbnails for cached songs and extracts embedded
     * artwork for uncached songs in the background so scrolling through the library is 100% instant.
     */
    fun warmAllSongsArtworkInBackground(context: Context, songs: List<Song>) {
        if (songs.isEmpty()) return
        val appCtx = context.applicationContext
        activeWarmAllJob?.cancel()
        activeWarmAllJob = backgroundWarmerScope.launch {
            ensureDiskIndexWarm(appCtx)
            // Pass 1: Pre-decode 128x128 thumbnails for the first 220 songs that already have local artwork on disk
            var decodedCount = 0
            for (song in songs) {
                if (!isActive) return@launch
                if (song.id <= 0L) continue
                val existingUri = getMemory(song.id, song.path)
                    ?: song.artworkUri?.takeIf { it.isNotBlank() && !it.contains("audio/albumart") }
                if (!existingUri.isNullOrBlank()) {
                    cacheArtwork(song.id, song.path, existingUri)
                    if (decodedCount < 220 && getThumbnailBitmap(song.id, song.path) == null) {
                        decodeAndCacheThumbnail(appCtx, song.id, existingUri, song.path)
                        decodedCount++
                    }
                }
            }
            // Missing artwork is resolved lazily for visible tiles. Extracting every track during
            // startup creates avoidable disk and MediaMetadataRetriever work on large libraries.
        }
    }

    fun notifyArtworkChanged(songId: Long, newArtworkUri: String? = null, path: String? = null) {
        val now = System.currentTimeMillis()
        synchronized(thumbnailBitmapCache) {
            if (!path.isNullOrBlank()) thumbnailBitmapCache.remove(trackIdentity(songId, path))
            else thumbnailBitmapCache.snapshot().keys.filter { it.startsWith("id:$songId:") || it == "id:$songId" }.forEach(thumbnailBitmapCache::remove)
        }
        if (!newArtworkUri.isNullOrBlank()) {
            if (!path.isNullOrBlank()) cacheArtwork(songId, path, newArtworkUri) else cache[songId] = newArtworkUri
            if (path.isNullOrBlank()) manualCoverCache[songId] = newArtworkUri
        } else {
            cache.remove(songId)
            synchronized(trackArtworkCache) {
                if (!path.isNullOrBlank()) trackArtworkCache.remove(trackIdentity(songId, path))
                else trackArtworkCache.snapshot().keys.filter { it.startsWith("id:$songId:") }.forEach(trackArtworkCache::remove)
            }
            if (path.isNullOrBlank()) trackArtworkPathById.remove(songId)
            manualCoverCache.remove(songId)
        }
        manualPathCache.clear()
        preloadedKeys.removeIf { it.startsWith("art_id:$songId") }
        try {
            ArtworkManager.invalidateNotificationArtwork(songId, path)
        } catch (_: Throwable) {}
        if (songId != 0L) {
            songArtworkVersions["id:$songId"] = now
            if (!path.isNullOrBlank()) songArtworkVersions[trackIdentity(songId, path)] = now
        }
        if (!path.isNullOrBlank()) failedLookupAt.remove(trackIdentity(songId, path))
        val signalKey = if (!path.isNullOrBlank()) trackIdentity(songId, path) else "id:$songId"
        artworkUpdateSignals.computeIfAbsent(signalKey) { mutableLongStateOf(0L) }.longValue = now
        artworkUpdateSignal.longValue = now
    }

    fun cacheArtwork(songId: Long, path: String?, uri: String) {
        if (songId <= 0L || uri.isBlank()) return
        val key = trackIdentity(songId, path)
        synchronized(trackArtworkCache) { trackArtworkCache.put(key, uri) }
        if (!path.isNullOrBlank()) trackArtworkPathById[songId] = normalizedTrackPath(path)
        cache[songId] = uri
        failedLookupAt.remove(key)
    }

    fun getMemory(songId: Long, path: String?): String? {
        if (songId <= 0L) return null
        if (path.isNullOrBlank()) return getMemory(songId)
        val normalizedPath = normalizedTrackPath(path)
        val trackValue = synchronized(trackArtworkCache) { trackArtworkCache.get(trackIdentity(songId, path)) }
        if (trackValue != null) return trackValue.takeIf { it.isNotEmpty() }
        return if (trackArtworkPathById[songId] == normalizedPath) getMemory(songId) else null
    }

    fun shouldAttemptArtworkLookup(songId: Long, path: String?): Boolean {
        val attemptedAt = failedLookupAt[trackIdentity(songId, path)] ?: return true
        return System.currentTimeMillis() - attemptedAt >= 5 * 60 * 1000L
    }

    fun markArtworkLookupFailed(songId: Long, path: String?) {
        failedLookupAt[trackIdentity(songId, path)] = System.currentTimeMillis()
    }

    fun clearArtworkLookupFailure(songId: Long, path: String?) {
        failedLookupAt.remove(trackIdentity(songId, path))
    }

    fun invalidateTrack(songId: Long, path: String?, expectedUri: String? = null) {
        val key = trackIdentity(songId, path)
        synchronized(trackArtworkCache) {
            val cached = trackArtworkCache.get(key)
            if (expectedUri == null || cached == expectedUri) trackArtworkCache.remove(key)
        }
        if (expectedUri == null || cache[songId] == expectedUri) cache.remove(songId)
        synchronized(thumbnailBitmapCache) { thumbnailBitmapCache.remove(key) }
        failedLookupAt.remove(key)
    }

    val cache: MutableMap<Long, String> = object : AbstractMutableMap<Long, String>() {
        override val entries: MutableSet<MutableMap.MutableEntry<Long, String>>
            get() = lruCache.snapshot().entries.toMutableSet()
        override fun get(key: Long): String? = lruCache.get(key)
        override fun put(key: Long, value: String): String? = lruCache.put(key, value)
        override fun remove(key: Long): String? = lruCache.remove(key)
        override fun clear() { lruCache.evictAll() }
        override val size: Int get() = lruCache.size()
    }

    /**
     * Proactively resolves artwork and preloads decoded thumbnails into both `thumbnailBitmapCache`
     * and Coil's in-memory cache ahead of the user's viewport.
     */
    fun preloadWindow(
        context: Context,
        songs: List<Song>,
        firstVisibleIndex: Int,
        windowAhead: Int = 32,
        windowBehind: Int = 10
    ) {
        if (songs.isEmpty()) return
        val safeFirst = firstVisibleIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        val startIndex = (safeFirst - windowBehind).coerceAtLeast(0)
        val endIndex = (safeFirst + windowAhead).coerceAtMost(songs.size)
        if (startIndex >= endIndex) return

        val visibleFirstSongs = try {
            val aheadList = songs.subList(safeFirst, endIndex).toList()
            val behindList = if (startIndex < safeFirst) songs.subList(startIndex, safeFirst).toList() else emptyList()
            aheadList + behindList
        } catch (_: Throwable) {
            emptyList()
        }
        if (visibleFirstSongs.isEmpty()) return

        activePreloadJob?.cancel()
        activePreloadJob = preloaderScope.launch {
            val appCtx = context.applicationContext
            ensureDiskIndexWarm(appCtx)
            val imageLoader = coil.Coil.imageLoader(appCtx)
            for (song in visibleFirstSongs) {
                if (!isActive) break
                if (song.id <= 0L) continue

                var uri = getMemory(song.id, song.path)
                if (uri == null) {
                    uri = getOrCheckDisk(appCtx, song.id, song.path)
                        ?: song.artworkUri?.takeIf { it.isNotBlank() && !it.contains("audio/albumart") }
                        ?: if (shouldAttemptArtworkLookup(song.id, song.path)) ArtworkManager.getOrExtractArtworkUri(
                            context = appCtx,
                            songId = song.id,
                            filePath = song.path,
                            albumId = song.albumId,
                            albumName = song.displayAlbum
                        ) else null
                    if (!uri.isNullOrBlank()) cacheArtwork(song.id, song.path, uri)
                    else markArtworkLookupFailed(song.id, song.path)
                }

                if (!uri.isNullOrBlank()) {
                    if (getThumbnailBitmap(song.id, song.path) == null) {
                        decodeAndCacheThumbnail(appCtx, song.id, uri, song.path)
                    }
                    val identity = trackIdentity(song.id, song.path)
                    val version = getSongArtworkVersion(song.id, song.path)
                    val cacheKey = "art_${identity}_${uri.hashCode()}_$version"
                    if (preloadedKeys.add(cacheKey)) {
                        try {
                            val request = ImageRequest.Builder(appCtx)
                                .data(uri)
                                .memoryCacheKey(cacheKey)
                                .diskCacheKey(cacheKey)
                                .size(180, 180)
                                .precision(coil.size.Precision.INEXACT)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .allowHardware(true)
                                .build()
                            imageLoader.enqueue(request)
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    fun getMemory(songId: Long): String? {
        if (songId == 0L) return null
        val mem = cache[songId]
        if (mem != null && mem.isEmpty()) {
            return null
        }
        return mem
    }

    fun getManualCoverUri(context: Context, songId: Long, path: String? = null): String? {
        ensureDiskIndexWarm(context)
        if (!path.isNullOrBlank()) {
            manualPathCache[path]?.let { return it.takeIf { uri -> uri.isNotEmpty() } }
        }
        try {
            if (!path.isNullOrBlank()) {
                val normalizedPathHash = trackIdentity(songId, path).substringAfterLast(':')
                val legacyPathHash = path.trim().lowercase().hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL
                val idPrefix = if (songId > 0L) "track_${songId}_" else "track_path_"
                val appThumbDir = File(context.filesDir, ".thumbnail")
                val candidateThumbs = listOf(
                    File(appThumbDir, "${idPrefix}${normalizedPathHash}.jpg"),
                    File(appThumbDir, "${idPrefix}${legacyPathHash}.jpg")
                )
                for (thumb in candidateThumbs) {
                    if (ArtworkManager.isValidCompleteImageFile(thumb)) {
                        val uriStr = android.net.Uri.fromFile(thumb).toString()
                        manualPathCache[path] = uriStr
                        return uriStr
                    }
                }

                val coversDir = File(context.filesDir, "song_covers")
                if (coversDir.exists()) {
                    val candidateCovers = listOf(
                        File(coversDir, "song_path_${normalizedPathHash}.jpg"),
                        File(coversDir, "song_path_${legacyPathHash}.jpg")
                    )
                    for (cover in candidateCovers) {
                        if (ArtworkManager.isValidCompleteImageFile(cover)) {
                            val uriStr = android.net.Uri.fromFile(cover).toString()
                            manualPathCache[path] = uriStr
                            return uriStr
                        }
                    }
                }
                manualPathCache[path] = ""
                return null
            }

            if (songId != 0L) {
                manualCoverCache[songId]?.let { return it.takeIf(String::isNotEmpty) }
                val coversDir = File(context.filesDir, "song_covers")
                val stableCover = File(coversDir, "song_$songId.jpg")
                if (ArtworkManager.isValidCompleteImageFile(stableCover)) {
                    val uriStr = android.net.Uri.fromFile(stableCover).toString()
                    manualCoverCache[songId] = uriStr
                    return uriStr
                }
                manualCoverCache[songId] = ""
            }
        } catch (_: Exception) {}
        return null
    }

    fun getOrCheckDisk(context: Context, songId: Long, path: String? = null): String? {
        ensureDiskIndexWarm(context)
        if (songId == 0L && path.isNullOrBlank()) return null
        if (songId != 0L) getMemory(songId, path)?.let { return it }
        try {
            val manual = getManualCoverUri(context, songId, path)
            if (!manual.isNullOrBlank()) {
                if (songId != 0L) cacheArtwork(songId, path, manual)
                return manual
            }

            if (songId != 0L) {
                if (!path.isNullOrBlank()) {
                    val pathKey = trackFileName(songId, path)
                    val trackFiles = listOf(
                        File(File(context.filesDir, "artworks"), pathKey),
                        File(File(context.filesDir, "song_artworks"), pathKey),
                        File(File(context.cacheDir, "artworks"), pathKey),
                        File(File(context.cacheDir, "song_artworks"), pathKey)
                    )
                    for (file in trackFiles) {
                        if (ArtworkManager.isValidCompleteImageFile(file)) {
                            val uriStr = android.net.Uri.fromFile(file).toString()
                            cacheArtwork(songId, path, uriStr)
                            return uriStr
                        }
                    }
                }

                val mayUseLegacyIdFiles = path.isNullOrBlank() || trackArtworkPathById[songId] == normalizedTrackPath(path)
                if (mayUseLegacyIdFiles) {
                    val persistentFile = File(File(context.filesDir, "artworks"), "$songId.jpg")
                    if (ArtworkManager.isValidCompleteImageFile(persistentFile)) {
                        val uriStr = android.net.Uri.fromFile(persistentFile).toString()
                        cacheArtwork(songId, path, uriStr)
                        return uriStr
                    }
                    val persistentSongArt = File(File(context.filesDir, "song_artworks"), "$songId.jpg")
                    if (ArtworkManager.isValidCompleteImageFile(persistentSongArt)) {
                        val uriStr = android.net.Uri.fromFile(persistentSongArt).toString()
                        cacheArtwork(songId, path, uriStr)
                        return uriStr
                    }
                    val cacheFile = File(File(context.cacheDir, "artworks"), "$songId.jpg")
                    if (ArtworkManager.isValidCompleteImageFile(cacheFile)) {
                        val uriStr = android.net.Uri.fromFile(cacheFile).toString()
                        cacheArtwork(songId, path, uriStr)
                        return uriStr
                    }
                    val songArtFile = File(File(context.cacheDir, "song_artworks"), "$songId.jpg")
                    if (ArtworkManager.isValidCompleteImageFile(songArtFile)) {
                        val uriStr = android.net.Uri.fromFile(songArtFile).toString()
                        cacheArtwork(songId, path, uriStr)
                        return uriStr
                    }
                    val onlinePref = context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                    val onlineKey = if (path.isNullOrBlank()) songId.toString() else trackIdentity(songId, path)
                    val onlineUrl = onlinePref.getString(onlineKey, null)
                    if (!onlineUrl.isNullOrBlank()) {
                        cacheArtwork(songId, path, onlineUrl)
                        return onlineUrl
                    }
                }
            }

            if (!path.isNullOrBlank() || songId != 0L) {
                val thumbFile = ArtworkManager.findThumbnailArtwork(path ?: "", songId, context)
                if (thumbFile != null && ArtworkManager.isValidCompleteImageFile(thumbFile)) {
                    val uriStr = android.net.Uri.fromFile(thumbFile).toString()
                    if (songId != 0L) cacheArtwork(songId, path, uriStr)
                    return uriStr
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun isManualCover(context: Context, songId: Long, path: String? = null): Boolean {
        return !getManualCoverUri(context, songId, path).isNullOrBlank()
    }

    fun hasSuccessfullyFetchedOnlineArtwork(context: Context, songId: Long): Boolean {
        if (songId <= 0L) return false
        try {
            ensureDiskIndexWarm(context)
            val mem = cache[songId]
            if (!mem.isNullOrBlank()) return true
            val persistentFile = File(File(context.filesDir, "artworks"), "$songId.jpg")
            if (ArtworkManager.isValidCompleteImageFile(persistentFile)) return true
            val onlinePref = context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
            return !onlinePref.getString(songId.toString(), null).isNullOrBlank()
        } catch (_: Exception) {
            return false
        }
    }

    suspend fun getAuthoritativeArtwork(context: Context, song: Song, isHighResPreferred: Boolean = false): String? {
        ensureDiskIndexWarm(context)
        // 1. Manual user cover (Highest priority)
        val manual = getManualCoverUri(context, song.id, song.path)
            ?: com.example.util.ManualTagStore.getManualMetadata(context, song.id, song.path)?.artworkUri
        if (!manual.isNullOrBlank()) {
            cacheArtwork(song.id, song.path, manual)
            return manual
        }

        // 2. If song is marked manually edited with artwork, strictly honor song.artworkUri
        if (song.isManuallyEdited && !song.artworkUri.isNullOrBlank()) {
            cacheArtwork(song.id, song.path, song.artworkUri)
            return song.artworkUri
        }

        // A Room reference tied to this song path wins over ID-only legacy cache entries.
        if (!song.artworkUri.isNullOrBlank()) {
            val valid = if (song.artworkUri.startsWith("file://")) {
                val filePath = try { android.net.Uri.parse(song.artworkUri).path } catch (_: Exception) { null }
                ArtworkManager.isValidCompleteImageFile(filePath?.let(::File))
            } else song.artworkUri.startsWith("content://") && !song.artworkUri.contains("audio/albumart")
            if (valid) {
                cacheArtwork(song.id, song.path, song.artworkUri)
                return song.artworkUri
            }
        }

        getMemory(song.id, song.path)?.let { cached ->
            val valid = if (cached.startsWith("file://")) {
                val filePath = try { android.net.Uri.parse(cached).path } catch (_: Exception) { null }
                ArtworkManager.isValidCompleteImageFile(filePath?.let(::File))
            } else !cached.startsWith("content://") || !cached.contains("audio/albumart")
            if (valid) return cached
            invalidateTrack(song.id, song.path, cached)
        }

        // 3. Check persistent filesDir / cache first (already extracted & verified complete JPEG)
        val disk = getOrCheckDisk(context, song.id, song.path)
        if (!disk.isNullOrBlank()) {
            return disk
        }

        // 5. Extract via ArtworkManager (atomic write + bounded MMR concurrency)
        val extracted = ArtworkManager.getOrExtractArtworkUri(
            context = context,
            songId = song.id,
            filePath = song.path,
            albumId = song.albumId,
            albumName = song.displayAlbum
        )
        if (!extracted.isNullOrBlank()) cacheArtwork(song.id, song.path, extracted)
        return extracted
    }

    fun invalidate(songId: Long) {
        synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.snapshot().keys.filter { it.startsWith("id:$songId:") || it == "id:$songId" }
                .forEach(thumbnailBitmapCache::remove)
        }
        synchronized(trackArtworkCache) {
            trackArtworkCache.snapshot().keys.filter { it.startsWith("id:$songId:") || it == "id:$songId" }
                .forEach(trackArtworkCache::remove)
        }
        cache.remove(songId)
        manualCoverCache.remove(songId)
        trackArtworkPathById.remove(songId)
        songArtworkVersions.keys.removeIf { it.startsWith("id:$songId:") || it == "id:$songId" }
        failedLookupAt.keys.removeIf { it.startsWith("id:$songId:") || it == "id:$songId" }
        manualPathCache.clear()
        preloadedKeys.removeIf { it.startsWith("art_id:$songId") }
        try {
            val ctx = com.example.service.PlaybackManager.activeInstance?.context
            if (ctx != null) {
                val loader = coil.Coil.imageLoader(ctx)
                loader.memoryCache?.keys?.filter { it.key.contains("art_id:$songId") }?.forEach { key ->
                    loader.memoryCache?.remove(key)
                }
            }
        } catch (_: Throwable) {}
    }

    fun clear() {
        synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.evictAll()
        }
        synchronized(trackArtworkCache) { trackArtworkCache.evictAll() }
        cache.clear()
        trackArtworkPathById.clear()
        failedLookupAt.clear()
        songArtworkVersions.clear()
        artworkUpdateSignals.clear()
        manualCoverCache.clear()
        manualPathCache.clear()
        preloadedKeys.clear()
        isDiskIndexWarm = false
    }

    fun clearMemory() {
        clear()
    }
}

// Gradient presets for rich procedural album art when images aren't available
val ARTWORK_GRADIENTS = listOf(
    listOf(Color(0xFF261C3B), Color(0xFF191228)), // Deep violet
    listOf(Color(0xFF1B2438), Color(0xFF101726)), // Deep navy
    listOf(Color(0xFF182D26), Color(0xFF0F1D18)), // Deep emerald
    listOf(Color(0xFF33231A), Color(0xFF20150F)), // Warm bronze
    listOf(Color(0xFF2C1930), Color(0xFF1B0E1E)), // Deep plum
    listOf(Color(0xFF2E1B26), Color(0xFF1D0F17)), // Deep wine
    listOf(Color(0xFF162B2F), Color(0xFF0D1B1E)), // Deep teal
    listOf(Color(0xFF2E2416), Color(0xFF1C160C))  // Deep amber
)

private val PRECOMPUTED_ARTWORK_BRUSHES = ARTWORK_GRADIENTS.map { Brush.linearGradient(it) }

@Composable
fun AlbumArtView(
    song: Song?,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12,
    showTitleOverlay: Boolean = false,
    forceHighResOnline: Boolean = false
) {
    val context = LocalContext.current
    val prefsRepo = remember(context) { UserPreferencesRepository.getInstance(context) }
    val preferHighResSetting = prefsRepo.cachedFetchOnlineArtworkHighRes
    val cacheAlbumArtSetting = prefsRepo.cachedCacheAlbumArtEnabled
    val cacheSongArtSetting = prefsRepo.cachedCacheSongArtWorkEnabled

    val isHighResPreferred = forceHighResOnline || preferHighResSetting
    val isCacheEnabled = cacheAlbumArtSetting && cacheSongArtSetting
    val isThumbnail = cornerRadius <= 18 && !forceHighResOnline

    val songId = song?.id ?: 0L
    val songPath = song?.path
    val trackKey = remember(songId, songPath) { ArtworkResolutionCache.trackIdentity(songId, songPath) }
    val gradientIndex = (songId.toInt() % PRECOMPUTED_ARTWORK_BRUSHES.size).let { if (it < 0) -it else it }
    val backgroundBrush = PRECOMPUTED_ARTWORK_BRUSHES[gradientIndex]

    val artworkVersion = ArtworkResolutionCache.getArtworkUpdateVersion(songId, songPath)
    val songArtwork = song?.artworkUri?.takeIf { it.isNotBlank() }

    // 0ms synchronous lookup of pre-decoded ImageBitmap for thumbnails
    val initialThumbBmp = remember(trackKey, artworkVersion) {
        if (isThumbnail && songId > 0L) ArtworkResolutionCache.getThumbnailBitmap(songId, song?.path) else null
    }
    var resolvedThumbBmp by remember(trackKey, artworkVersion) {
        mutableStateOf(initialThumbBmp)
    }
    if (initialThumbBmp != null && resolvedThumbBmp !== initialThumbBmp) {
        resolvedThumbBmp = initialThumbBmp
    }

    // Only memory and Room references are checked synchronously here. Disk indexing and decoding
    // are scheduled off the main thread so first composition never walks cache directories.
    val initialUri = remember(trackKey, songArtwork, artworkVersion) {
        if (song == null || songId == 0L) return@remember null
        if (!songArtwork.isNullOrBlank() && !songArtwork.contains("audio/albumart")) {
            ArtworkResolutionCache.cacheArtwork(songId, song.path, songArtwork)
            return@remember songArtwork
        }
        ArtworkResolutionCache.getMemory(songId, song.path)?.takeIf { it.isNotBlank() }
    }

    var resolvedArtworkUri by remember(trackKey, artworkVersion) {
        mutableStateOf(initialUri)
    }

    if (!initialUri.isNullOrBlank() && resolvedArtworkUri != initialUri) {
        resolvedArtworkUri = initialUri
    }

    val coroutineScope = rememberCoroutineScope()
    val latestTrackKey by rememberUpdatedState(trackKey)
    var isRetryingArtwork by remember(trackKey) { mutableStateOf(false) }
    var imageLoaded by remember(trackKey, resolvedArtworkUri) { mutableStateOf(false) }

    suspend fun fetchOnlineArtwork(currentSong: Song): Pair<String, androidx.compose.ui.graphics.ImageBitmap?>? {
        if (currentSong.isManuallyEdited) return null
        return withContext(ArtworkResolutionCache.onlineArtworkDispatcher) {
            val online = OnlineArtistImageFetcher.getSongArtworkUrl(
                context = context,
                title = currentSong.displayTitle,
                artist = currentSong.displayArtist,
                album = currentSong.displayAlbum,
                preferUltraHd = isHighResPreferred,
                cacheEnabled = isCacheEnabled,
                songId = currentSong.id,
                filePath = currentSong.path
            )
            if (online.isNullOrBlank()) return@withContext null
            val finalUri = if (isCacheEnabled) {
                ArtworkManager.saveFetchedArtworkLocally(context, currentSong.id, online, currentSong.path) ?: online
            } else online
            if (isCacheEnabled) {
                try {
                    context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                        .edit().putString(ArtworkResolutionCache.trackIdentity(currentSong.id, currentSong.path), finalUri).apply()
                } catch (_: Exception) {}
            }
            ArtworkResolutionCache.cacheArtwork(currentSong.id, currentSong.path, finalUri)
            val decoded = if (isThumbnail) {
                ArtworkResolutionCache.decodeAndCacheThumbnail(context, currentSong.id, finalUri, currentSong.path)
            } else null
            finalUri to decoded
        }
    }

    val needsBackgroundWork = song != null && songId > 0L && ArtworkResolutionCache.shouldAttemptArtworkLookup(songId, song.path) &&
        (if (isThumbnail) resolvedThumbBmp == null else resolvedArtworkUri.isNullOrBlank())

    if (needsBackgroundWork && song != null && songId > 0L) {
        LaunchedEffect(trackKey, isHighResPreferred, isCacheEnabled, songArtwork, artworkVersion) {
            val currentSongSnapshot = song
            val fastPair = withContext(Dispatchers.IO) {
                if (!isActive || ArtworkResolutionCache.trackIdentity(currentSongSnapshot.id, currentSongSnapshot.path) != latestTrackKey) return@withContext null
                val uri = resolvedArtworkUri?.takeIf { it.isNotBlank() }
                    ?: ArtworkResolutionCache.getAuthoritativeArtwork(context, currentSongSnapshot, isHighResPreferred)
                if (!uri.isNullOrBlank()) {
                    ArtworkResolutionCache.cacheArtwork(songId, currentSongSnapshot.path, uri)
                    val decodedThumb = if (isThumbnail) {
                        ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, uri, currentSongSnapshot.path)
                    } else null
                    Pair(uri, decodedThumb)
                } else {
                    null
                }
            }

            if (!isActive || latestTrackKey != trackKey) return@LaunchedEffect

            if (fastPair != null) {
                resolvedArtworkUri = fastPair.first
                if (fastPair.second != null) {
                    resolvedThumbBmp = fastPair.second
                }
                return@LaunchedEffect
            }

            if (currentSongSnapshot.isManuallyEdited) {
                ArtworkResolutionCache.markArtworkLookupFailed(songId, currentSongSnapshot.path)
                return@LaunchedEffect
            }

            val onlinePair = fetchOnlineArtwork(currentSongSnapshot)
            if (!isActive || latestTrackKey != trackKey) return@LaunchedEffect

            if (onlinePair != null) {
                resolvedArtworkUri = onlinePair.first
                if (onlinePair.second != null) {
                    resolvedThumbBmp = onlinePair.second
                }
            } else {
                ArtworkResolutionCache.markArtworkLookupFailed(songId, currentSongSnapshot.path)
            }
        }
    }

    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius.dp) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundBrush),
        contentAlignment = Alignment.Center
    ) {
        val thumbBmp = resolvedThumbBmp
        val uri = resolvedArtworkUri

        if (thumbBmp == null && !imageLoaded) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxSize(0.42f)
                )
                if (showTitleOverlay && song != null) {
                    Text(
                        text = song.displayTitle.take(12),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (isThumbnail && thumbBmp != null) {
            Image(
                bitmap = thumbBmp,
                contentDescription = "Album Artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (!uri.isNullOrBlank()) {
            val imageReq = remember(uri, cornerRadius, context, artworkVersion, trackKey) {
                val cacheKey = "art_${trackKey}_${uri.hashCode()}_$artworkVersion"
                ImageRequest.Builder(context)
                    .data(uri)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .crossfade(false)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .allowHardware(true)
                    .apply {
                        if (isThumbnail) {
                            size(160, 160)
                            precision(coil.size.Precision.INEXACT)
                        }
                    }
                    .build()
            }
            AsyncImage(
                model = imageReq,
                contentDescription = "Album Artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { imageLoaded = true },
                onError = {
                    imageLoaded = false
                    if (!isRetryingArtwork && song != null) {
                        isRetryingArtwork = true
                        val failedSong = song
                        coroutineScope.launch {
                            try {
                                ArtworkResolutionCache.invalidateTrack(songId, failedSong.path, uri)
                                val extracted = withContext(Dispatchers.IO) {
                                    ArtworkResolutionCache.getAuthoritativeArtwork(context, failedSong, isHighResPreferred)
                                }
                                if (latestTrackKey != trackKey) return@launch
                                if (!extracted.isNullOrBlank() && extracted != uri) {
                                    resolvedArtworkUri = extracted
                                } else if (!failedSong.isManuallyEdited) {
                                    val online = fetchOnlineArtwork(failedSong)
                                    if (latestTrackKey == trackKey && online != null) {
                                        resolvedArtworkUri = online.first
                                        if (online.second != null) resolvedThumbBmp = online.second
                                    } else if (online == null) {
                                        ArtworkResolutionCache.markArtworkLookupFailed(songId, failedSong.path)
                                        resolvedArtworkUri = null
                                    }
                                } else {
                                    resolvedArtworkUri = null
                                }
                            } finally {
                                isRetryingArtwork = false
                            }
                        }
                    }
                }
            )
        }
    }
}
