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
    private val manualCoverCache = ConcurrentHashMap<Long, String>()
    private val manualPathCache = ConcurrentHashMap<String, String>()
    private val preloadedKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val preloaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(6))
    private val backgroundWarmerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    val onlineArtworkDispatcher = Dispatchers.IO.limitedParallelism(2)

    // Pre-decoded 112x112 ImageBitmap cache (~24MB max, holds ~450 song thumbnails in RAM for 0ms synchronous rendering)
    private val thumbnailBitmapCache = object : android.util.LruCache<Long, androidx.compose.ui.graphics.ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: androidx.compose.ui.graphics.ImageBitmap): Int {
            return (value.width * value.height * 4).coerceAtLeast(1024)
        }
    }

    @Volatile
    private var isDiskIndexWarm: Boolean = false
    private val diskIndexLock = Any()

    @Volatile
    private var activePreloadJob: Job? = null
    @Volatile
    private var activeWarmAllJob: Job? = null

    val artworkUpdateSignal = mutableLongStateOf(0L)
    private val songArtworkVersions = ConcurrentHashMap<Long, Long>()

    fun getSongArtworkVersion(songId: Long): Long = songArtworkVersions[songId] ?: 0L

    fun getThumbnailBitmap(songId: Long): androidx.compose.ui.graphics.ImageBitmap? {
        if (songId <= 0L) return null
        return synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.get(songId)
        }
    }

    fun putThumbnailBitmap(songId: Long, imageBitmap: androidx.compose.ui.graphics.ImageBitmap) {
        if (songId <= 0L) return
        synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.put(songId, imageBitmap)
        }
    }

    fun decodeAndCacheThumbnail(context: Context, songId: Long, uriStr: String): androidx.compose.ui.graphics.ImageBitmap? {
        if (songId <= 0L || uriStr.isBlank()) return null
        getThumbnailBitmap(songId)?.let { return it }
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
                putThumbnailBitmap(songId, imgBmp)
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
                val thumbDir = File(appCtx.filesDir, ".thumbnail")
                if (thumbDir.exists()) {
                    thumbDir.listFiles()?.forEach { f ->
                        val name = f.name
                        if (name.startsWith("track_") && name.endsWith(".jpg") && f.length() >= 256L) {
                            val core = name.removePrefix("track_").removeSuffix(".jpg")
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
                val existingUri = cache[song.id]
                    ?: song.artworkUri?.takeIf { it.isNotBlank() && !it.contains("audio/albumart") }
                if (!existingUri.isNullOrBlank()) {
                    cache[song.id] = existingUri
                    if (decodedCount < 220 && getThumbnailBitmap(song.id) == null) {
                        decodeAndCacheThumbnail(appCtx, song.id, existingUri)
                        decodedCount++
                    }
                }
            }
            // Pass 2: Proactively extract embedded artwork to disk for songs not yet in the cache
            for (song in songs) {
                if (!isActive) return@launch
                if (song.id <= 0L) continue
                if (cache[song.id] != null) continue
                val extracted = getAuthoritativeArtwork(appCtx, song, false)
                if (!extracted.isNullOrBlank()) {
                    cache[song.id] = extracted
                    if (decodedCount < 320 && getThumbnailBitmap(song.id) == null) {
                        decodeAndCacheThumbnail(appCtx, song.id, extracted)
                        decodedCount++
                    }
                } else {
                    cache[song.id] = ""
                }
            }
        }
    }

    fun notifyArtworkChanged(songId: Long, newArtworkUri: String? = null) {
        val now = System.currentTimeMillis()
        synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.remove(songId)
        }
        if (!newArtworkUri.isNullOrBlank()) {
            cache[songId] = newArtworkUri
            manualCoverCache[songId] = newArtworkUri
        } else {
            cache.remove(songId)
            manualCoverCache.remove(songId)
        }
        manualPathCache.clear()
        preloadedKeys.removeIf { it.startsWith("art_${songId}_") }
        try {
            ArtworkManager.invalidateNotificationArtwork(songId)
        } catch (_: Throwable) {}
        if (songId != 0L) {
            songArtworkVersions[songId] = now
        }
        artworkUpdateSignal.longValue = now
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

                var uri = cache[song.id]
                if (uri == null) {
                    uri = getOrCheckDisk(appCtx, song.id, song.path)
                        ?: song.artworkUri?.takeIf { it.isNotBlank() && !it.contains("audio/albumart") }
                        ?: ArtworkManager.getOrExtractArtworkUri(
                            context = appCtx,
                            songId = song.id,
                            filePath = song.path,
                            albumId = song.albumId,
                            albumName = song.displayAlbum
                        )
                    cache[song.id] = uri ?: ""
                }

                if (!uri.isNullOrBlank()) {
                    if (getThumbnailBitmap(song.id) == null) {
                        decodeAndCacheThumbnail(appCtx, song.id, uri)
                    }
                    val version = getSongArtworkVersion(song.id)
                    val cacheKey = "art_${song.id}_${uri.hashCode()}_$version"
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
        if (songId != 0L) {
            manualCoverCache[songId]?.let { return if (it.isEmpty()) null else it }
        }
        if (!path.isNullOrBlank()) {
            manualPathCache[path]?.let { return if (it.isEmpty()) null else it }
        }
        try {
            val pathHash = if (!path.isNullOrBlank()) {
                path.trim().lowercase().hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL
            } else {
                songId and 0x7FFFFFFF_FFFFFFFFL
            }
            val uniqueThumbBase = if (songId > 0L) "track_${songId}_${pathHash}" else "track_path_${pathHash}"

            val appThumbDir = File(context.filesDir, ".thumbnail")
            if (appThumbDir.exists()) {
                val exactThumb = File(appThumbDir, "$uniqueThumbBase.jpg")
                if (ArtworkManager.isValidCompleteImageFile(exactThumb)) {
                    val uriStr = android.net.Uri.fromFile(exactThumb).toString()
                    if (songId != 0L) manualCoverCache[songId] = uriStr
                    if (!path.isNullOrBlank()) manualPathCache[path] = uriStr
                    return uriStr
                }
            }

            val coversDir = File(context.filesDir, "song_covers")
            if (coversDir.exists()) {
                if (songId != 0L) {
                    val stableCover = File(coversDir, "song_$songId.jpg")
                    if (ArtworkManager.isValidCompleteImageFile(stableCover)) {
                        val uriStr = android.net.Uri.fromFile(stableCover).toString()
                        manualCoverCache[songId] = uriStr
                        return uriStr
                    }
                }
                if (!path.isNullOrBlank()) {
                    val pathCover = File(coversDir, "song_path_${pathHash}.jpg")
                    if (ArtworkManager.isValidCompleteImageFile(pathCover)) {
                        val uriStr = android.net.Uri.fromFile(pathCover).toString()
                        manualPathCache[path] = uriStr
                        return uriStr
                    }
                }
            }
            if (songId != 0L) manualCoverCache[songId] = ""
            if (!path.isNullOrBlank()) manualPathCache[path] = ""
        } catch (_: Exception) {}
        return null
    }

    fun getOrCheckDisk(context: Context, songId: Long, path: String? = null): String? {
        ensureDiskIndexWarm(context)
        if (songId == 0L && path.isNullOrBlank()) return null
        if (songId != 0L) {
            val mem = cache[songId]
            if (mem != null) {
                return if (mem.isEmpty()) null else mem
            }
        }
        try {
            val manual = getManualCoverUri(context, songId, path)
            if (!manual.isNullOrBlank()) {
                if (songId != 0L) cache[songId] = manual
                return manual
            }

            if (songId != 0L) {
                val persistentFile = File(File(context.filesDir, "artworks"), "$songId.jpg")
                if (ArtworkManager.isValidCompleteImageFile(persistentFile)) {
                    val uriStr = android.net.Uri.fromFile(persistentFile).toString()
                    cache[songId] = uriStr
                    return uriStr
                }
                val persistentSongArt = File(File(context.filesDir, "song_artworks"), "$songId.jpg")
                if (ArtworkManager.isValidCompleteImageFile(persistentSongArt)) {
                    val uriStr = android.net.Uri.fromFile(persistentSongArt).toString()
                    cache[songId] = uriStr
                    return uriStr
                }
                val cacheFile = File(File(context.cacheDir, "artworks"), "$songId.jpg")
                if (ArtworkManager.isValidCompleteImageFile(cacheFile)) {
                    val uriStr = android.net.Uri.fromFile(cacheFile).toString()
                    cache[songId] = uriStr
                    return uriStr
                }
                val songArtFile = File(File(context.cacheDir, "song_artworks"), "$songId.jpg")
                if (ArtworkManager.isValidCompleteImageFile(songArtFile)) {
                    val uriStr = android.net.Uri.fromFile(songArtFile).toString()
                    cache[songId] = uriStr
                    return uriStr
                }
                val onlinePref = context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                val onlineUrl = onlinePref.getString(songId.toString(), null)
                if (!onlineUrl.isNullOrBlank()) {
                    cache[songId] = onlineUrl
                    return onlineUrl
                }
            }

            if (!path.isNullOrBlank() || songId != 0L) {
                val thumbFile = ArtworkManager.findThumbnailArtwork(path ?: "", songId, context)
                if (thumbFile != null && ArtworkManager.isValidCompleteImageFile(thumbFile)) {
                    val uriStr = android.net.Uri.fromFile(thumbFile).toString()
                    if (songId != 0L) cache[songId] = uriStr
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
        if (song.id > 0L) {
            val mem = cache[song.id]
            if (mem != null) {
                return if (mem.isEmpty()) null else mem
            }
        }

        // 1. Manual user cover (Highest priority)
        val manual = getManualCoverUri(context, song.id, song.path)
            ?: com.example.util.ManualTagStore.getManualMetadata(context, song.id, song.path)?.artworkUri
        if (!manual.isNullOrBlank()) {
            if (song.id > 0L) cache[song.id] = manual
            return manual
        }

        // 2. If song is marked manually edited with artwork, strictly honor song.artworkUri
        if (song.isManuallyEdited && !song.artworkUri.isNullOrBlank()) {
            if (song.id > 0L) cache[song.id] = song.artworkUri
            return song.artworkUri
        }

        // 3. Check persistent filesDir / cache first (already extracted & verified complete JPEG)
        val disk = getOrCheckDisk(context, song.id, song.path)
        if (!disk.isNullOrBlank()) {
            return disk
        }

        // 4. Existing validated song.artworkUri (if file:// or valid content://)
        if (!song.artworkUri.isNullOrBlank()) {
            if (song.artworkUri.startsWith("file://")) {
                val p = try { android.net.Uri.parse(song.artworkUri).path } catch (_: Exception) { null }
                val f = if (p != null) File(p) else null
                if (ArtworkManager.isValidCompleteImageFile(f)) {
                    if (song.id > 0L) cache[song.id] = song.artworkUri
                    return song.artworkUri
                }
            } else if (song.artworkUri.startsWith("content://")) {
                val isDeprecatedAlbumArt = song.artworkUri.contains("audio/albumart") && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                if (!isDeprecatedAlbumArt) {
                    if (song.id > 0L) cache[song.id] = song.artworkUri
                    return song.artworkUri
                }
            }
        }

        // 5. Extract via ArtworkManager (atomic write + bounded MMR concurrency)
        val extracted = ArtworkManager.getOrExtractArtworkUri(
            context = context,
            songId = song.id,
            filePath = song.path,
            albumId = song.albumId,
            albumName = song.displayAlbum
        )
        if (!extracted.isNullOrBlank() && song.id > 0L) {
            cache[song.id] = extracted
        }
        return extracted
    }

    fun invalidate(songId: Long) {
        synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.remove(songId)
        }
        cache.remove(songId)
        manualCoverCache.remove(songId)
        manualPathCache.clear()
        preloadedKeys.removeIf { it.startsWith("art_${songId}_") }
        try {
            val ctx = com.example.service.PlaybackManager.activeInstance?.context
            if (ctx != null) {
                val loader = coil.Coil.imageLoader(ctx)
                loader.memoryCache?.keys?.filter { it.key.contains("art_${songId}_") }?.forEach { key ->
                    loader.memoryCache?.remove(key)
                }
            }
        } catch (_: Throwable) {}
    }

    fun clear() {
        synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.evictAll()
        }
        cache.clear()
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
    val gradientIndex = (songId.toInt() % PRECOMPUTED_ARTWORK_BRUSHES.size).let { if (it < 0) -it else it }
    val backgroundBrush = PRECOMPUTED_ARTWORK_BRUSHES[gradientIndex]

    val globalArtworkSignal = ArtworkResolutionCache.artworkUpdateSignal.longValue
    val artworkVersion = remember(songId, globalArtworkSignal) {
        ArtworkResolutionCache.getSongArtworkVersion(songId)
    }
    val songArtwork = song?.artworkUri?.takeIf { it.isNotBlank() }

    // 0ms synchronous lookup of pre-decoded ImageBitmap for thumbnails
    val initialThumbBmp = remember(songId, artworkVersion) {
        if (isThumbnail && songId > 0L) ArtworkResolutionCache.getThumbnailBitmap(songId) else null
    }
    var resolvedThumbBmp by remember(songId, artworkVersion) {
        mutableStateOf(initialThumbBmp)
    }
    if (initialThumbBmp != null && resolvedThumbBmp !== initialThumbBmp) {
        resolvedThumbBmp = initialThumbBmp
    }

    // 0ms synchronous URI lookup (warmed from disk index at startup)
    val initialUri = remember(songId, songArtwork, artworkVersion) {
        if (song == null || songId == 0L) return@remember null
        ArtworkResolutionCache.ensureDiskIndexWarm(context)
        val mem = ArtworkResolutionCache.getMemory(songId)
        if (!mem.isNullOrBlank()) return@remember mem

        if (!songArtwork.isNullOrBlank() && !songArtwork.contains("audio/albumart")) {
            ArtworkResolutionCache.cache[songId] = songArtwork
            return@remember songArtwork
        }
        null
    }

    var resolvedArtworkUri by remember(songId, artworkVersion) {
        mutableStateOf(initialUri)
    }

    if (!initialUri.isNullOrBlank() && resolvedArtworkUri != initialUri) {
        resolvedArtworkUri = initialUri
    }

    val coroutineScope = rememberCoroutineScope()

    // Only launch background extraction/decoding if we don't already have the thumbnail bitmap (for thumbnails) or URI (for full-res)
    val needsBackgroundWork = if (isThumbnail) {
        resolvedThumbBmp == null && ArtworkResolutionCache.cache[songId] != ""
    } else {
        resolvedArtworkUri.isNullOrBlank() && ArtworkResolutionCache.cache[songId] != ""
    }

    if (needsBackgroundWork && song != null && songId > 0L) {
        LaunchedEffect(songId, isHighResPreferred, isCacheEnabled, songArtwork, song.path, artworkVersion) {
            val currentSongSnapshot = song
            val fastPair = withContext(Dispatchers.IO) {
                if (!isActive || currentSongSnapshot.id != songId) return@withContext null
                val uri = resolvedArtworkUri?.takeIf { it.isNotBlank() }
                    ?: ArtworkResolutionCache.getAuthoritativeArtwork(context, currentSongSnapshot, isHighResPreferred)
                if (!uri.isNullOrBlank()) {
                    ArtworkResolutionCache.cache[songId] = uri
                    val decodedThumb = if (isThumbnail) {
                        ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, uri)
                    } else null
                    Pair(uri, decodedThumb)
                } else {
                    null
                }
            }

            if (!isActive || currentSongSnapshot.id != songId) return@LaunchedEffect

            if (fastPair != null) {
                resolvedArtworkUri = fastPair.first
                if (fastPair.second != null) {
                    resolvedThumbBmp = fastPair.second
                }
                return@LaunchedEffect
            }

            val isActiveTrack = com.example.service.PlaybackManager.activeInstance?.playbackState?.value?.currentSong?.id == songId
            if (!forceHighResOnline && !isActiveTrack) {
                ArtworkResolutionCache.cache[songId] = ""
                return@LaunchedEffect
            }

            val onlinePair = withContext(ArtworkResolutionCache.onlineArtworkDispatcher) {
                if (!isActive || currentSongSnapshot.id != songId) return@withContext null
                val online = OnlineArtistImageFetcher.getSongArtworkUrl(
                    context = context,
                    title = currentSongSnapshot.displayTitle,
                    artist = currentSongSnapshot.displayArtist,
                    album = currentSongSnapshot.displayAlbum,
                    preferUltraHd = isHighResPreferred,
                    cacheEnabled = isCacheEnabled,
                    songId = currentSongSnapshot.id
                )
                if (!online.isNullOrBlank()) {
                    val savedLocal = ArtworkManager.saveFetchedArtworkLocally(context, currentSongSnapshot.id, online)
                    val finalUri = savedLocal ?: online
                    if (isCacheEnabled) {
                        try {
                            context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                                .edit().putString(currentSongSnapshot.id.toString(), finalUri).apply()
                        } catch (_: Exception) {}
                    }
                    ArtworkResolutionCache.cache[songId] = finalUri
                    val decodedThumb = if (isThumbnail) {
                        ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, finalUri)
                    } else null
                    Pair(finalUri, decodedThumb)
                } else {
                    if (resolvedArtworkUri.isNullOrBlank()) {
                        ArtworkResolutionCache.cache[songId] = ""
                    }
                    null
                }
            }

            if (!isActive || currentSongSnapshot.id != songId) return@LaunchedEffect

            if (onlinePair != null) {
                resolvedArtworkUri = onlinePair.first
                if (onlinePair.second != null) {
                    resolvedThumbBmp = onlinePair.second
                }
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
        val cachedBmp = remember(songId, artworkVersion, uri) {
            if (thumbBmp != null) null else ArtworkManager.getCachedNotificationBitmap(songId)
        }

        if (isThumbnail && thumbBmp != null) {
            Image(
                bitmap = thumbBmp,
                contentDescription = "Album Artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (!uri.isNullOrBlank()) {
            val imageReq = remember(uri, cornerRadius, context, artworkVersion, cachedBmp) {
                val cacheKey = "art_${songId}_${uri.hashCode()}_$artworkVersion"
                ImageRequest.Builder(context)
                    .data(uri)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .crossfade(false)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .allowHardware(true)
                    .apply {
                        if (cachedBmp != null && !cachedBmp.isRecycled) {
                            placeholder(BitmapDrawable(context.resources, cachedBmp))
                            error(BitmapDrawable(context.resources, cachedBmp))
                        }
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
                onError = {
                    coroutineScope.launch(Dispatchers.IO) {
                        val reExtracted = ArtworkManager.getOrExtractArtworkUri(
                            context,
                            songId,
                            song?.path ?: "",
                            song?.albumId ?: 0L,
                            song?.displayAlbum ?: ""
                        )
                        withContext(Dispatchers.Main) {
                            if (!reExtracted.isNullOrBlank() && reExtracted != uri) {
                                ArtworkResolutionCache.cache[songId] = reExtracted
                                resolvedArtworkUri = reExtracted
                            }
                        }
                    }
                }
            )
        } else if (cachedBmp != null && !cachedBmp.isRecycled) {
            Image(
                bitmap = cachedBmp.asImageBitmap(),
                contentDescription = "Album Artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
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
    }
}