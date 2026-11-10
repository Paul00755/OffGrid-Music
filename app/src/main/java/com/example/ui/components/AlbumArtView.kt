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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
object ArtworkResolutionCache {
    private val lruCache = object : android.util.LruCache<Long, String>(8000) {}
    private val trackArtworkCache = object : android.util.LruCache<String, String>(8000) {}
    private val trackIdentityCache = object : android.util.LruCache<Long, Pair<String, String>>(8000) {}
    private val trackArtworkPathById = ConcurrentHashMap<Long, String>()
    private val manualCoverCache = ConcurrentHashMap<Long, String>()
    private val manualPathCache = ConcurrentHashMap<String, String>()
    private val preloadedKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val highResArtworkKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val inFlightOnlineFetches = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val inFlightOnlineDeferreds = ConcurrentHashMap<String, Deferred<Pair<String, androidx.compose.ui.graphics.ImageBitmap?>?>>()
    private val failedLookupAt = ConcurrentHashMap<String, Long>()
    private val preloaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(6))
    private val backgroundWarmerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    val onlineArtworkDispatcher = Dispatchers.IO.limitedParallelism(2)
    private val versionCounter = AtomicLong(System.currentTimeMillis())

    fun nextVersionStamp(): Long {
        val now = System.currentTimeMillis()
        while (true) {
            val prev = versionCounter.get()
            val next = if (now > prev) now else prev + 1L
            if (versionCounter.compareAndSet(prev, next)) return next
        }
    }

    // Pre-decoded 128x128 RGB_565 ImageBitmap cache (~48MB max, holds ~750+ song thumbnails in RAM for 0ms synchronous rendering)
    private val thumbnailBitmapCache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap): Int {
            return (value.width * value.height * 2).coerceAtLeast(1024)
        }
    }

    @Volatile
    private var isDiskIndexWarm: Boolean = false
    private val diskIndexLock = Any()
    @Volatile private var diskIndexWarmJob: Job? = null

    fun ensureDiskIndexWarmAsync(context: Context) {
        if (isDiskIndexWarm || diskIndexWarmJob?.isActive == true) return
        val appCtx = context.applicationContext
        diskIndexWarmJob = backgroundWarmerScope.launch {
            ensureDiskIndexWarm(appCtx)
        }
    }

    @Volatile
    private var activePreloadJob: Job? = null
    @Volatile
    private var activeWarmAllJob: Job? = null

    val artworkUpdateSignal = mutableLongStateOf(0L)
    val thumbnailDecodedSignal = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val songArtworkVersions = ConcurrentHashMap<String, Long>()
    private val artworkUpdateSignals = ConcurrentHashMap<String, androidx.compose.runtime.MutableLongState>()
    private val thumbnailUpdateSignals = ConcurrentHashMap<String, androidx.compose.runtime.MutableLongState>()

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
    private val sha256ThreadLocal = object : ThreadLocal<java.security.MessageDigest>() {
        override fun initialValue(): java.security.MessageDigest =
            java.security.MessageDigest.getInstance("SHA-256")
    }

    private fun bytesToHexFast(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = HEX_CHARS[v ushr 4]
            hexChars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun normalizedTrackPath(path: String?): String = path.orEmpty().trim().replace('\\', '/')

    fun trackIdentity(songId: Long, path: String?): String {
        val normalizedPath = normalizedTrackPath(path)
        if (normalizedPath.isBlank()) return "id:$songId"
        synchronized(trackIdentityCache) {
            trackIdentityCache.get(songId)?.let { (cachedPath, identity) ->
                if (cachedPath == normalizedPath) return identity
            }
        }
        val md = sha256ThreadLocal.get() ?: java.security.MessageDigest.getInstance("SHA-256")
        md.reset()
        val digest = bytesToHexFast(md.digest(normalizedPath.toByteArray(Charsets.UTF_8)))
        val identity = "id:$songId:$digest"
        synchronized(trackIdentityCache) {
            trackIdentityCache.put(songId, normalizedPath to identity)
        }
        return identity
    }

    fun trackFileName(songId: Long, path: String?): String =
        "${songId}_${trackIdentity(songId, path).substringAfterLast(':')}.jpg"

    fun getSongArtworkVersion(songId: Long): Long = songArtworkVersions["id:$songId"] ?: 0L

    fun getSongArtworkVersion(songId: Long, path: String?): Long {
        val trackVer = songArtworkVersions[trackIdentity(songId, path)] ?: 0L
        val idVer = getSongArtworkVersion(songId)
        return maxOf(trackVer, idVer)
    }

    fun getArtworkUpdateVersion(songId: Long, path: String?): Long {
        if (songId <= 0L) return 0L
        val trackSig = artworkUpdateSignals.computeIfAbsent(trackIdentity(songId, path)) { mutableLongStateOf(0L) }.longValue
        val idSig = artworkUpdateSignals.computeIfAbsent("id:$songId") { mutableLongStateOf(0L) }.longValue
        return maxOf(trackSig, idSig)
    }

    fun getThumbnailUpdateVersion(songId: Long, path: String?): Long {
        if (songId <= 0L) return 0L
        val trackSig = thumbnailUpdateSignals.computeIfAbsent(trackIdentity(songId, path)) { mutableLongStateOf(0L) }.longValue
        val idSig = thumbnailUpdateSignals.computeIfAbsent("id:$songId") { mutableLongStateOf(0L) }.longValue
        return maxOf(trackSig, idSig)
    }

    fun getThumbnailBitmap(songId: Long, path: String? = null): androidx.compose.ui.graphics.ImageBitmap? {
        if (songId <= 0L) return null
        return synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.get(trackIdentity(songId, path))
                ?: thumbnailBitmapCache.get("id:$songId")
        }
    }

    fun putThumbnailBitmap(songId: Long, imageBitmap: androidx.compose.ui.graphics.ImageBitmap, path: String? = null) {
        if (songId <= 0L) return
        val identity = trackIdentity(songId, path)
        var changed = false
        val knownPath = trackArtworkPathById[songId]
        val knownIdentity = if (!knownPath.isNullOrBlank()) trackIdentity(songId, knownPath) else null
        synchronized(thumbnailBitmapCache) {
            val prev = thumbnailBitmapCache.get(identity)
            changed = prev !== imageBitmap
            thumbnailBitmapCache.put(identity, imageBitmap)
            if (!path.isNullOrBlank()) {
                if (knownPath == null || knownPath == normalizedTrackPath(path)) {
                    thumbnailBitmapCache.put("id:$songId", imageBitmap)
                }
            } else {
                thumbnailBitmapCache.put("id:$songId", imageBitmap)
                if (knownIdentity != null) {
                    thumbnailBitmapCache.put(knownIdentity, imageBitmap)
                }
            }
        }
        val stamp = nextVersionStamp()
        thumbnailDecodedSignal.value = stamp
        if (changed) {
            thumbnailUpdateSignals.computeIfAbsent(identity) { mutableLongStateOf(0L) }.longValue = stamp
            thumbnailUpdateSignals.computeIfAbsent("id:$songId") { mutableLongStateOf(0L) }.longValue = stamp
            if (knownIdentity != null && knownIdentity != identity) {
                thumbnailUpdateSignals.computeIfAbsent(knownIdentity) { mutableLongStateOf(0L) }.longValue = stamp
            }
        }
    }

    fun cacheDecodedBitmapAsThumbnail(
        songId: Long,
        sourceBitmap: android.graphics.Bitmap,
        path: String? = null
    ): androidx.compose.ui.graphics.ImageBitmap? {
        if (songId <= 0L || sourceBitmap.isRecycled) return null
        return try {
            val thumbBmp = if (sourceBitmap.width > 160 || sourceBitmap.height > 160) {
                android.graphics.Bitmap.createScaledBitmap(sourceBitmap, 128, 128, true)
            } else {
                sourceBitmap
            }
            val imgBmp = thumbBmp.asImageBitmap()
            putThumbnailBitmap(songId, imgBmp, path)
            imgBmp
        } catch (_: Throwable) {
            null
        }
    }

    fun isSupportedArtworkUriSyntax(uriStr: String?): Boolean {
        if (uriStr.isNullOrBlank()) return false
        if (uriStr.startsWith("file://")) return true
        if (uriStr.startsWith("content://")) return !uriStr.contains("audio/albumart")
        return uriStr.startsWith("http://") || uriStr.startsWith("https://")
    }

    fun isKnownValidLocalUri(uriStr: String?): Boolean {
        if (uriStr.isNullOrBlank()) return false
        if (uriStr.startsWith("file://")) {
            val filePath = try { android.net.Uri.parse(uriStr).path } catch (_: Exception) { null }
            return ArtworkManager.isValidCompleteImageFile(filePath?.let(::File))
        }
        if (uriStr.startsWith("content://")) {
            return !uriStr.contains("audio/albumart")
        }
        return uriStr.startsWith("http://") || uriStr.startsWith("https://")
    }

    fun hasHighResArtwork(context: Context, songId: Long, path: String? = null): Boolean {
        if (songId <= 0L) return false
        val key = trackIdentity(songId, path)
        if (highResArtworkKeys.contains(key) || highResArtworkKeys.contains("id:$songId")) return true
        return try {
            val prefs = context.applicationContext.getSharedPreferences("song_artworks_online_highres_v2", Context.MODE_PRIVATE)
            val has = prefs.getBoolean(key, false) || prefs.getBoolean("id:$songId", false)
            if (has) highResArtworkKeys.add(key)
            has
        } catch (_: Throwable) {
            false
        }
    }

    fun markHighResArtworkCached(context: Context, songId: Long, path: String? = null, uri: String? = null) {
        if (songId <= 0L) return
        val key = trackIdentity(songId, path)
        highResArtworkKeys.add(key)
        highResArtworkKeys.add("id:$songId")
        try {
            val prefs = context.applicationContext.getSharedPreferences("song_artworks_online_highres_v2", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(key, true)
                .putBoolean("id:$songId", true)
                .apply()
        } catch (_: Throwable) {}
    }

    fun tryAcquireOnlineFetch(songId: Long, path: String?, preferHighRes: Boolean): Boolean {
        val key = "${trackIdentity(songId, path)}_${if (preferHighRes) "hd" else "std"}"
        return inFlightOnlineFetches.add(key)
    }

    fun releaseOnlineFetch(songId: Long, path: String?, preferHighRes: Boolean) {
        val key = "${trackIdentity(songId, path)}_${if (preferHighRes) "hd" else "std"}"
        inFlightOnlineFetches.remove(key)
    }

    suspend fun fetchOrAwaitOnlineArtwork(
        context: Context,
        song: Song,
        upgradeToHighRes: Boolean,
        isCacheEnabled: Boolean
    ): Pair<String, androidx.compose.ui.graphics.ImageBitmap?>? {
        if (song.id <= 0L) return null
        if (com.example.util.ManualTagStore.isArtworkManuallyEdited(context, song.id, song.path)) return null
        val appCtx = context.applicationContext
        val key = "${trackIdentity(song.id, song.path)}_${if (upgradeToHighRes) "hd" else "std"}"
        val existingDeferred = inFlightOnlineDeferreds[key]
        if (existingDeferred != null && existingDeferred.isActive) {
            return try {
                existingDeferred.await()
            } catch (_: Throwable) {
                null
            }
        }
        val createdDeferred = preloaderScope.async(onlineArtworkDispatcher) {
            if (!tryAcquireOnlineFetch(song.id, song.path, upgradeToHighRes)) {
                return@async null
            }
            val startGen = com.example.util.ManualTagStore.getArtworkEditGeneration(appCtx, song.id, song.path)
            try {
                val online = OnlineArtistImageFetcher.getSongArtworkUrl(
                    context = appCtx,
                    title = song.displayTitle,
                    artist = song.displayArtist,
                    album = song.displayAlbum,
                    preferUltraHd = upgradeToHighRes,
                    cacheEnabled = isCacheEnabled,
                    songId = song.id,
                    filePath = song.path
                )
                if (online.isNullOrBlank()) return@async null
                if (com.example.util.ManualTagStore.isArtworkManuallyEdited(appCtx, song.id, song.path) ||
                    com.example.util.ManualTagStore.getArtworkEditGeneration(appCtx, song.id, song.path) != startGen
                ) {
                    return@async null
                }
                val finalUri = if (isCacheEnabled) {
                    ArtworkManager.saveFetchedArtworkLocally(
                        context = appCtx,
                        songId = song.id,
                        url = online,
                        filePath = song.path,
                        isHighRes = upgradeToHighRes,
                        expectedArtworkGeneration = startGen
                    ) ?: online
                } else online
                if (isCacheEnabled) {
                    try {
                        appCtx.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                            .edit().putString(trackIdentity(song.id, song.path), finalUri).apply()
                    } catch (_: Exception) {}
                }
                cacheArtwork(song.id, song.path, finalUri)
                val decoded = decodeAndCacheThumbnail(appCtx, song.id, finalUri, song.path, forceReplace = true)
                    ?: getThumbnailBitmap(song.id, song.path)
                notifyArtworkChanged(
                    songId = song.id,
                    newArtworkUri = finalUri,
                    path = song.path,
                    preserveThumbnailCache = decoded != null
                )
                val activePlayback = com.example.service.PlaybackManager.activeInstance
                if (activePlayback?.playbackState?.value?.currentSong?.id == song.id) {
                    activePlayback.updateCurrentSongArtwork(song.id, finalUri, song.path)
                }
                finalUri to decoded
            } finally {
                releaseOnlineFetch(song.id, song.path, upgradeToHighRes)
                inFlightOnlineDeferreds.remove(key)
            }
        }
        inFlightOnlineDeferreds[key] = createdDeferred
        return try {
            createdDeferred.await()
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeAndCacheThumbnail(
        context: Context,
        songId: Long,
        uriStr: String,
        path: String? = null,
        forceReplace: Boolean = false
    ): androidx.compose.ui.graphics.ImageBitmap? {
        if (songId <= 0L || uriStr.isBlank()) return null
        if (!forceReplace) {
            getThumbnailBitmap(songId, path)?.let { return it }
        }
        return try {
            val bmp = if (uriStr.startsWith("file://")) {
                val filePath = android.net.Uri.parse(uriStr).path ?: return null
                val f = File(filePath)
                if (!f.exists() || f.length() < 256L) return null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(filePath, bounds)
                val sample = ArtworkManager.calculateInSampleSize(bounds, 128, 128)
                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                android.graphics.BitmapFactory.decodeFile(filePath, decodeOpts)
            } else if (uriStr.startsWith("content://") && !uriStr.contains("audio/albumart")) {
                ArtworkManager.decodeBitmapFromUri(context, uriStr, 128)
            } else {
                null
            }
            if (bmp != null && !bmp.isRecycled) {
                val compactBmp = if (bmp.width > 128 || bmp.height > 128) {
                    try {
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 112, 112, true)
                        if (scaled !== bmp) bmp.recycle()
                        scaled
                    } catch (_: Throwable) {
                        bmp
                    }
                } else {
                    bmp
                }
                val imgBmp = compactBmp.asImageBitmap()
                putThumbnailBitmap(songId, imgBmp, path)
                if (!path.isNullOrBlank()) {
                    putThumbnailBitmap(songId, imgBmp, null)
                }
                imgBmp
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Populates the in-memory artwork URI index from existing local disk directories in a single fast pass (< 1ms).
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
                            val stem = name.removeSuffix(".jpg")
                            val uriStr = android.net.Uri.fromFile(f).toString()
                            if (stem.contains('_')) {
                                val idPart = stem.substringBefore('_').toLongOrNull()
                                val digestPart = stem.substringAfter('_')
                                if (idPart != null && idPart > 0L && digestPart.isNotBlank()) {
                                    synchronized(trackArtworkCache) {
                                        trackArtworkCache.put("id:$idPart:$digestPart", uriStr)
                                    }
                                    cache[idPart] = uriStr
                                }
                            } else {
                                val idPart = stem.toLongOrNull()
                                if (idPart != null && idPart > 0L) {
                                    cache[idPart] = uriStr
                                }
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
            val prefsRepo = UserPreferencesRepository.getInstance(appCtx)
            // Pass 1: Pre-decode compact thumbnails for the first 600 songs that already have local artwork on disk
            var decodedCount = 0
            for (song in songs) {
                if (!isActive) return@launch
                if (song.id <= 0L) continue
                val existingUri = getMemory(song.id, song.path)
                    ?: song.artworkUri?.takeIf { it.isNotBlank() && !it.contains("audio/albumart") }
                if (!existingUri.isNullOrBlank()) {
                    cacheArtwork(song.id, song.path, existingUri)
                    if (decodedCount < 600 && getThumbnailBitmap(song.id, song.path) == null) {
                        decodeAndCacheThumbnail(appCtx, song.id, existingUri, song.path)
                        decodedCount++
                    }
                }
            }

            // Pass 2: Respect Fetch Hi-Res Artwork policy across the library:
            // - When Fetch Hi-Res Artwork is OFF: only auto-fetch online if the song has NO local/embedded artwork.
            // - When Fetch Hi-Res Artwork is ON: try to fetch hi-res online artwork for all songs except manually edited ones.
            for (song in songs) {
                if (!isActive) return@launch
                if (song.id <= 0L) continue
                if (com.example.util.ManualTagStore.isArtworkManuallyEdited(appCtx, song.id, song.path)) continue

                val preferHighRes = prefsRepo.cachedFetchOnlineArtworkHighRes
                val cacheEnabled = prefsRepo.cachedCacheAlbumArtEnabled && prefsRepo.cachedCacheSongArtWorkEnabled
                val localUri = getAuthoritativeArtwork(appCtx, song, preferHighRes)
                if (!localUri.isNullOrBlank() && getThumbnailBitmap(song.id, song.path) == null) {
                    decodeAndCacheThumbnail(appCtx, song.id, localUri, song.path)
                }

                val isMissingArtwork = localUri.isNullOrBlank()
                val needsOnlineFetch = if (preferHighRes) {
                    !hasHighResArtwork(appCtx, song.id, song.path)
                } else {
                    isMissingArtwork
                }

                if (needsOnlineFetch &&
                    shouldAttemptArtworkLookup(song.id, song.path) &&
                    tryAcquireOnlineFetch(song.id, song.path, preferHighRes)
                ) {
                    val startGen = com.example.util.ManualTagStore.getArtworkEditGeneration(appCtx, song.id, song.path)
                    try {
                        val onlineUrl = OnlineArtistImageFetcher.getSongArtworkUrl(
                            context = appCtx,
                            title = song.displayTitle,
                            artist = song.displayArtist,
                            album = song.displayAlbum,
                            preferUltraHd = preferHighRes,
                            cacheEnabled = cacheEnabled,
                            songId = song.id,
                            filePath = song.path
                        )
                        if (!onlineUrl.isNullOrBlank() &&
                            !com.example.util.ManualTagStore.isArtworkManuallyEdited(appCtx, song.id, song.path) &&
                            com.example.util.ManualTagStore.getArtworkEditGeneration(appCtx, song.id, song.path) == startGen
                        ) {
                            val finalUri = if (cacheEnabled) {
                                ArtworkManager.saveFetchedArtworkLocally(
                                    context = appCtx,
                                    songId = song.id,
                                    url = onlineUrl,
                                    filePath = song.path,
                                    isHighRes = preferHighRes,
                                    expectedArtworkGeneration = startGen
                                ) ?: onlineUrl
                            } else onlineUrl
                            if (finalUri.isNotBlank()) {
                                cacheArtwork(song.id, song.path, finalUri)
                                decodeAndCacheThumbnail(appCtx, song.id, finalUri, song.path, forceReplace = true)
                            }
                        } else if (onlineUrl.isNullOrBlank() && isMissingArtwork) {
                            markArtworkLookupFailed(song.id, song.path)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        releaseOnlineFetch(song.id, song.path, preferHighRes)
                    }
                }
            }
        }
    }

    private fun decodeAndCacheThumbnailFromFileUri(
        songId: Long,
        uriStr: String,
        path: String? = null
    ): androidx.compose.ui.graphics.ImageBitmap? {
        if (songId <= 0L || !uriStr.startsWith("file://")) return null
        return try {
            val filePath = android.net.Uri.parse(uriStr).path ?: return null
            val f = File(filePath)
            if (!f.exists() || f.length() < 256L) return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(filePath, bounds)
            val sample = ArtworkManager.calculateInSampleSize(bounds, 128, 128)
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val bmp = android.graphics.BitmapFactory.decodeFile(filePath, decodeOpts)
            if (bmp != null && !bmp.isRecycled) {
                val compactBmp = if (bmp.width > 128 || bmp.height > 128) {
                    try {
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 112, 112, true)
                        if (scaled !== bmp) bmp.recycle()
                        scaled
                    } catch (_: Throwable) {
                        bmp
                    }
                } else {
                    bmp
                }
                val imgBmp = compactBmp.asImageBitmap()
                putThumbnailBitmap(songId, imgBmp, path)
                if (!path.isNullOrBlank()) {
                    putThumbnailBitmap(songId, imgBmp, null)
                }
                imgBmp
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    fun notifyArtworkChanged(
        songId: Long,
        newArtworkUri: String? = null,
        path: String? = null,
        preserveThumbnailCache: Boolean = false
    ) {
        val now = nextVersionStamp()
        val identity = trackIdentity(songId, path)
        val knownPath = trackArtworkPathById[songId]
        val knownIdentity = if (!knownPath.isNullOrBlank()) trackIdentity(songId, knownPath) else null
        if (!newArtworkUri.isNullOrBlank()) {
            cacheArtwork(songId, path, newArtworkUri)
            if (path.isNullOrBlank()) manualCoverCache[songId] = newArtworkUri
            if (newArtworkUri.startsWith("file://")) {
                if (!preserveThumbnailCache || getThumbnailBitmap(songId, path) == null) {
                    decodeAndCacheThumbnailFromFileUri(songId, newArtworkUri, path)
                }
            } else if (!preserveThumbnailCache) {
                synchronized(thumbnailBitmapCache) {
                    if (!path.isNullOrBlank()) {
                        thumbnailBitmapCache.remove(identity)
                    }
                    thumbnailBitmapCache.snapshot().keys
                        .filter { it.startsWith("id:$songId:") || it == "id:$songId" }
                        .forEach(thumbnailBitmapCache::remove)
                }
            }
        } else {
            synchronized(thumbnailBitmapCache) {
                if (!path.isNullOrBlank()) {
                    thumbnailBitmapCache.remove(identity)
                }
                thumbnailBitmapCache.snapshot().keys
                    .filter { it.startsWith("id:$songId:") || it == "id:$songId" }
                    .forEach(thumbnailBitmapCache::remove)
            }
            cache.remove(songId)
            synchronized(trackArtworkCache) {
                if (!path.isNullOrBlank()) trackArtworkCache.remove(identity)
                else trackArtworkCache.snapshot().keys.filter { it.startsWith("id:$songId:") }.forEach(trackArtworkCache::remove)
            }
            if (path.isNullOrBlank()) trackArtworkPathById.remove(songId)
            manualCoverCache.remove(songId)
        }
        manualPathCache.clear()
        preloadedKeys.removeIf { it.startsWith("art_id:$songId") || it.startsWith("art_thumb_id:$songId") || it.startsWith("art_full_id:$songId") }
        try {
            ArtworkManager.invalidateNotificationArtwork(songId, path)
        } catch (_: Throwable) {}
        try {
            PlaylistCoverCache.invalidateAutoCollageCache()
        } catch (_: Throwable) {}
        try {
            val ctx = com.example.service.PlaybackManager.activeInstance?.context
            if (ctx != null) {
                val loader = coil.Coil.imageLoader(ctx)
                loader.memoryCache?.keys?.filter {
                    it.key.contains("id:$songId") || it.key.contains("art_${songId}_")
                }?.forEach { key ->
                    loader.memoryCache?.remove(key)
                }
            }
        } catch (_: Throwable) {}
        if (songId != 0L) {
            songArtworkVersions["id:$songId"] = now
            songArtworkVersions[identity] = now
            if (knownIdentity != null) songArtworkVersions[knownIdentity] = now
        }
        if (!path.isNullOrBlank()) failedLookupAt.remove(identity)
        if (knownIdentity != null) failedLookupAt.remove(knownIdentity)
        failedLookupAt.remove("id:$songId")
        artworkUpdateSignals.computeIfAbsent(identity) { mutableLongStateOf(0L) }.longValue = now
        artworkUpdateSignals.computeIfAbsent("id:$songId") { mutableLongStateOf(0L) }.longValue = now
        if (knownIdentity != null && knownIdentity != identity) {
            artworkUpdateSignals.computeIfAbsent(knownIdentity) { mutableLongStateOf(0L) }.longValue = now
        }
        thumbnailUpdateSignals.computeIfAbsent(identity) { mutableLongStateOf(0L) }.longValue = now
        thumbnailUpdateSignals.computeIfAbsent("id:$songId") { mutableLongStateOf(0L) }.longValue = now
        if (knownIdentity != null && knownIdentity != identity) {
            thumbnailUpdateSignals.computeIfAbsent(knownIdentity) { mutableLongStateOf(0L) }.longValue = now
        }
        thumbnailDecodedSignal.value = now
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
        val knownPathForId = trackArtworkPathById[songId]
        return if (knownPathForId == null || knownPathForId == normalizedPath) getMemory(songId) else null
    }

    fun shouldAttemptArtworkLookup(songId: Long, path: String?): Boolean {
        val attemptedAt = failedLookupAt[trackIdentity(songId, path)] ?: return true
        return System.currentTimeMillis() - attemptedAt >= 30 * 1000L
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
        synchronized(thumbnailBitmapCache) {
            thumbnailBitmapCache.remove(key)
            thumbnailBitmapCache.remove("id:$songId")
        }
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
                        ?: song.artworkUri?.takeIf { isKnownValidLocalUri(it) }
                        ?: if (shouldAttemptArtworkLookup(song.id, song.path)) ArtworkManager.getOrExtractArtworkUri(
                            context = appCtx,
                            songId = song.id,
                            filePath = song.path,
                            albumId = song.albumId,
                            albumName = song.displayAlbum
                        ) else null
                    if (!uri.isNullOrBlank()) cacheArtwork(song.id, song.path, uri)
                }

                if (!uri.isNullOrBlank()) {
                    val thumb = getThumbnailBitmap(song.id, song.path)
                        ?: decodeAndCacheThumbnail(appCtx, song.id, uri, song.path)
                    // Only enqueue Coil ImageRequest when thumbnailBitmapCache could not decode directly (e.g. remote http/https URLs)
                    if (thumb == null) {
                        val identity = trackIdentity(song.id, song.path)
                        val version = getSongArtworkVersion(song.id, song.path)
                        val cacheKey = "art_thumb_${identity}_${uri.hashCode()}_$version"
                        if (preloadedKeys.add(cacheKey)) {
                            try {
                                val request = ImageRequest.Builder(appCtx)
                                    .data(uri)
                                    .memoryCacheKey(cacheKey)
                                    .diskCacheKey(cacheKey)
                                    .size(160, 160)
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
        val manualMeta = com.example.util.ManualTagStore.getManualMetadata(context, songId, path.orEmpty())
        if (manualMeta != null) {
            if (!manualMeta.artworkEdited) return null
            if (!manualMeta.artworkUri.isNullOrBlank()) {
                val uriStr = manualMeta.artworkUri
                val isValid = if (uriStr.startsWith("file://")) {
                    ArtworkManager.isValidCompleteImageFile(android.net.Uri.parse(uriStr).path?.let(::File))
                } else true
                if (isValid) return uriStr
            }
        }
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

                val knownPath = trackArtworkPathById[songId]
                val mayUseLegacyIdFiles = path.isNullOrBlank() || knownPath == null || knownPath == normalizedTrackPath(path)
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
                    val onlineUrl = onlinePref.getString(onlineKey, null) ?: onlinePref.getString(songId.toString(), null)
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
        if (com.example.util.ManualTagStore.isArtworkManuallyEdited(context, songId, path.orEmpty())) return true
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
        // 1. Manual user cover (Highest priority — only when artwork itself was manually edited)
        if (com.example.util.ManualTagStore.isArtworkManuallyEdited(context, song.id, song.path)) {
            val manual = com.example.util.ManualTagStore.getManualMetadata(context, song.id, song.path)?.artworkUri
                ?: getManualCoverUri(context, song.id, song.path)
            if (!manual.isNullOrBlank()) {
                cacheArtwork(song.id, song.path, manual)
                return manual
            }
        } else {
            val legacyManual = getManualCoverUri(context, song.id, song.path)
            if (!legacyManual.isNullOrBlank()) {
                cacheArtwork(song.id, song.path, legacyManual)
                return legacyManual
            }
        }

        // If artwork was updated during this session, prefer the updated in-memory cache over a potentially stale Song.artworkUri snapshot
        if (getSongArtworkVersion(song.id, song.path) > 0L) {
            getMemory(song.id, song.path)?.let { cached ->
                val valid = if (cached.startsWith("file://")) {
                    val filePath = try { android.net.Uri.parse(cached).path } catch (_: Exception) { null }
                    ArtworkManager.isValidCompleteImageFile(filePath?.let(::File))
                } else !cached.startsWith("content://") || !cached.contains("audio/albumart")
                if (valid) return cached
                invalidateTrack(song.id, song.path, cached)
            }
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
        preloadedKeys.removeIf { it.startsWith("art_id:$songId") || it.startsWith("art_thumb_id:$songId") || it.startsWith("art_full_id:$songId") }
        try {
            val ctx = com.example.service.PlaybackManager.activeInstance?.context
            if (ctx != null) {
                val loader = coil.Coil.imageLoader(ctx)
                loader.memoryCache?.keys?.filter { it.key.contains("id:$songId") }?.forEach { key ->
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
        thumbnailUpdateSignals.clear()
        manualCoverCache.clear()
        manualPathCache.clear()
        preloadedKeys.clear()
        highResArtworkKeys.clear()
        inFlightOnlineFetches.clear()
        inFlightOnlineDeferreds.clear()
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
    val prefsRepo = remember(context) {
        ArtworkResolutionCache.ensureDiskIndexWarmAsync(context)
        UserPreferencesRepository.getInstance(context)
    }
    val preferHighResSetting by prefsRepo.fetchOnlineArtworkHighRes.collectAsState(initial = prefsRepo.cachedFetchOnlineArtworkHighRes)
    val cacheAlbumArtSetting = prefsRepo.cachedCacheAlbumArtEnabled
    val cacheSongArtSetting = prefsRepo.cachedCacheSongArtWorkEnabled

    val isHighResPreferred = preferHighResSetting
    val isCacheEnabled = cacheAlbumArtSetting && cacheSongArtSetting
    val isThumbnail = !forceHighResOnline

    val songId = song?.id ?: 0L
    val songPath = song?.path
    val trackKey = song?.trackIdentityKey ?: remember(songId, songPath) {
        ArtworkResolutionCache.trackIdentity(songId, songPath)
    }
    val gradientIndex = (songId.toInt() % PRECOMPUTED_ARTWORK_BRUSHES.size).let { if (it < 0) -it else it }
    val backgroundBrush = PRECOMPUTED_ARTWORK_BRUSHES[gradientIndex]

    val perSongArtworkSignal = ArtworkResolutionCache.getArtworkUpdateVersion(songId, songPath)
    val perSongThumbSignal = ArtworkResolutionCache.getThumbnailUpdateVersion(songId, songPath)
    val artworkVersion = remember(trackKey, perSongArtworkSignal) {
        ArtworkResolutionCache.getSongArtworkVersion(songId, songPath)
    }
    val songArtwork = song?.artworkUri?.takeIf { it.isNotBlank() }

    // 0ms synchronous lookup of pre-decoded ImageBitmap for both thumbnails and full-res views
    val initialThumbBmp = remember(trackKey, artworkVersion, perSongArtworkSignal, perSongThumbSignal) {
        if (songId > 0L) {
            ArtworkResolutionCache.getThumbnailBitmap(songId, song?.path)
                ?: ArtworkResolutionCache.getThumbnailBitmap(songId, null)
        } else null
    }
    // Keyed strictly by trackKey so same-song artwork updates never flash to a blank/dark tile,
    // while composable slot reuse for a different song immediately resets state!
    var resolvedThumbBmp by remember(trackKey) {
        mutableStateOf(initialThumbBmp)
    }
    if (initialThumbBmp != null && resolvedThumbBmp !== initialThumbBmp) {
        resolvedThumbBmp = initialThumbBmp
    } else if (initialThumbBmp == null && artworkVersion > 0L && perSongArtworkSignal > 0L && songArtwork == null && ArtworkResolutionCache.getMemory(songId, songPath) == null) {
        resolvedThumbBmp = null
    }

    val initialUri = remember(trackKey, songArtwork, artworkVersion, perSongArtworkSignal, perSongThumbSignal, isThumbnail) {
        if (song == null || songId == 0L) return@remember null
        val mem = ArtworkResolutionCache.getMemory(songId, song.path)?.takeIf {
            ArtworkResolutionCache.isSupportedArtworkUriSyntax(it)
        }
        if (artworkVersion > 0L && !mem.isNullOrBlank()) {
            return@remember mem
        }
        if (!songArtwork.isNullOrBlank() && ArtworkResolutionCache.isSupportedArtworkUriSyntax(songArtwork)) {
            return@remember songArtwork
        }
        if (!mem.isNullOrBlank()) {
            return@remember mem
        }
        if (!isThumbnail) {
            ArtworkResolutionCache.getOrCheckDisk(context, songId, song.path)?.takeIf {
                ArtworkResolutionCache.isSupportedArtworkUriSyntax(it)
            }
        } else null
    }

    var resolvedArtworkUri by remember(trackKey) {
        mutableStateOf(initialUri)
    }

    if (!initialUri.isNullOrBlank() && resolvedArtworkUri != initialUri) {
        resolvedArtworkUri = initialUri
    }

    val coroutineScope = rememberCoroutineScope()
    val latestTrackKey by rememberUpdatedState(trackKey)
    var isRetryingArtwork by remember(trackKey) { mutableStateOf(false) }
    var asyncImageLoaded by remember(trackKey, resolvedArtworkUri, artworkVersion) { mutableStateOf(false) }

    suspend fun fetchOnlineArtwork(currentSong: Song, upgradeToHighRes: Boolean = isHighResPreferred): Pair<String, androidx.compose.ui.graphics.ImageBitmap?>? {
        return ArtworkResolutionCache.fetchOrAwaitOnlineArtwork(
            context = context,
            song = currentSong,
            upgradeToHighRes = upgradeToHighRes,
            isCacheEnabled = isCacheEnabled
        )
    }

    val needsHighResUpgrade = remember(isHighResPreferred, songId, songPath, artworkVersion) {
        if (!isHighResPreferred || song == null || songId <= 0L) {
            false
        } else {
            !com.example.util.ManualTagStore.isArtworkManuallyEdited(context, songId, song.path) &&
                !ArtworkResolutionCache.hasHighResArtwork(context, songId, song.path)
        }
    }

    val needsBackgroundWork = song != null && songId > 0L &&
        (!isThumbnail || resolvedThumbBmp == null || needsHighResUpgrade)

    var localDecodeAttempted by remember(trackKey, resolvedArtworkUri, artworkVersion) { mutableStateOf(false) }

    if (needsBackgroundWork && song != null && songId > 0L) {
        LaunchedEffect(trackKey, isThumbnail, isHighResPreferred, isCacheEnabled, songArtwork, artworkVersion) {
            val currentSongSnapshot = song
            if (isThumbnail && resolvedArtworkUri.isNullOrBlank() && resolvedThumbBmp == null) {
                // Tiny yield when URI is not yet in memory so items flung past at 120Hz cancel before touching disk I/O
                kotlinx.coroutines.delay(12L)
                if (!isActive || latestTrackKey != trackKey) return@LaunchedEffect
            }
            // Step 1: Resolve local/cached artwork URI and thumbnail bitmap immediately without blocking on full-res MMR upgrade
            val fastPair = withContext(Dispatchers.IO) {
                if (!isActive || currentSongSnapshot.trackIdentityKey != latestTrackKey) return@withContext null
                val latestMem = ArtworkResolutionCache.getMemory(songId, currentSongSnapshot.path)?.takeIf {
                    ArtworkResolutionCache.isKnownValidLocalUri(it)
                }
                var candidateUri = if (artworkVersion > 0L && !latestMem.isNullOrBlank()) {
                    latestMem
                } else {
                    resolvedArtworkUri?.takeIf { ArtworkResolutionCache.isKnownValidLocalUri(it) } ?: latestMem
                }
                if (candidateUri.isNullOrBlank()) {
                    candidateUri = ArtworkResolutionCache.getAuthoritativeArtwork(context, currentSongSnapshot, isHighResPreferred)
                }
                if (!candidateUri.isNullOrBlank()) {
                    var decodedThumb = ArtworkResolutionCache.getThumbnailBitmap(songId, currentSongSnapshot.path)
                        ?: ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, candidateUri, currentSongSnapshot.path)
                    // If candidateUri was a stale file:// or uncached http:// URL that couldn't decode into a thumbnail,
                    // re-resolve authoritative embedded/local artwork from the audio file
                    if (decodedThumb == null) {
                        if (candidateUri.startsWith("http://") || candidateUri.startsWith("https://")) {
                            val savedLocal = ArtworkManager.saveFetchedArtworkLocally(
                                context = context,
                                songId = songId,
                                url = candidateUri,
                                filePath = currentSongSnapshot.path,
                                isHighRes = isHighResPreferred
                            )
                            if (!savedLocal.isNullOrBlank()) {
                                candidateUri = savedLocal
                                decodedThumb = ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, savedLocal, currentSongSnapshot.path, forceReplace = true)
                            }
                        } else {
                            ArtworkResolutionCache.invalidateTrack(songId, currentSongSnapshot.path, candidateUri)
                            val reExtracted = ArtworkResolutionCache.getAuthoritativeArtwork(context, currentSongSnapshot, isHighResPreferred)
                            if (!reExtracted.isNullOrBlank()) {
                                candidateUri = reExtracted
                                decodedThumb = ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, reExtracted, currentSongSnapshot.path, forceReplace = true)
                            } else {
                                candidateUri = null
                            }
                        }
                    }
                    if (!candidateUri.isNullOrBlank()) {
                        ArtworkResolutionCache.cacheArtwork(songId, currentSongSnapshot.path, candidateUri)
                        Pair(candidateUri, decodedThumb)
                    } else null
                } else {
                    null
                }
            }

            if (!isActive || latestTrackKey != trackKey) return@LaunchedEffect

            localDecodeAttempted = true
            if (fastPair != null) {
                if (resolvedArtworkUri != fastPair.first) {
                    resolvedArtworkUri = fastPair.first
                }
                if (fastPair.second != null) {
                    resolvedThumbBmp = fastPair.second
                }
                // Step 2: For full-res views (Now Playing / Hero Spotlight), upgrade embedded artwork in the background
                // AFTER the fast thumbnail/URI is already displayed on screen
                if (!isThumbnail) {
                    val upgradedPair = withContext(Dispatchers.IO) {
                        if (!isActive || currentSongSnapshot.trackIdentityKey != latestTrackKey) return@withContext null
                        val upUri = ArtworkManager.upgradeEmbeddedArtworkToFullResIfNeeded(
                            context = context,
                            songId = songId,
                            filePath = currentSongSnapshot.path,
                            currentUri = fastPair.first
                        )
                        if (!upUri.isNullOrBlank()) {
                            val upThumb = ArtworkResolutionCache.decodeAndCacheThumbnail(
                                context = context,
                                songId = songId,
                                uriStr = upUri,
                                path = currentSongSnapshot.path,
                                forceReplace = true
                            )
                            upUri to upThumb
                        } else null
                    }
                    if (isActive && latestTrackKey == trackKey && upgradedPair != null) {
                        ArtworkResolutionCache.cacheArtwork(songId, currentSongSnapshot.path, upgradedPair.first)
                        resolvedArtworkUri = upgradedPair.first
                        if (upgradedPair.second != null) {
                            resolvedThumbBmp = upgradedPair.second
                        }
                    }
                }
                val hasHighResNow = ArtworkResolutionCache.hasHighResArtwork(context, songId, currentSongSnapshot.path)
                if ((!needsHighResUpgrade || hasHighResNow) && (!isThumbnail || fastPair.second != null)) {
                    return@LaunchedEffect
                }
            }

            if (com.example.util.ManualTagStore.isArtworkManuallyEdited(context, songId, currentSongSnapshot.path)) {
                return@LaunchedEffect
            }

            if (!ArtworkResolutionCache.shouldAttemptArtworkLookup(songId, currentSongSnapshot.path)) {
                return@LaunchedEffect
            }

            val onlinePair = fetchOnlineArtwork(currentSongSnapshot, isHighResPreferred)
            if (!isActive || latestTrackKey != trackKey) return@LaunchedEffect

            if (onlinePair != null) {
                resolvedArtworkUri = onlinePair.first
                if (onlinePair.second != null) {
                    resolvedThumbBmp = onlinePair.second
                }
            } else if (fastPair == null) {
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

        if (thumbBmp == null && !asyncImageLoaded) {
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

        // Always render the pre-decoded thumbnail bitmap immediately (for both thumbnails and as a 0ms instant layer
        // behind full-res Now Playing / Hero Spotlight while Coil decodes the 1440x1440 full-res image)
        if (thumbBmp != null && (isThumbnail || !asyncImageLoaded)) {
            Image(
                bitmap = thumbBmp,
                contentDescription = "Album Artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        if ((!isThumbnail || thumbBmp == null) && !uri.isNullOrBlank() && (!isThumbnail || !uri.startsWith("file://") || localDecodeAttempted)) {
            val imageReq = remember(uri, cornerRadius, context, artworkVersion, trackKey, isThumbnail) {
                val sizeTag = if (isThumbnail) "thumb" else "full"
                val cacheKey = "art_${sizeTag}_${trackKey}_${uri.hashCode()}_$artworkVersion"
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
                        } else {
                            size(1440, 1440)
                            precision(coil.size.Precision.INEXACT)
                            bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
                        }
                    }
                    .build()
            }
            AsyncImage(
                model = imageReq,
                contentDescription = "Album Artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    asyncImageLoaded = true
                    if (songId > 0L && resolvedThumbBmp == null) {
                        val bmp = (state.result.drawable as? BitmapDrawable)?.bitmap
                        if (bmp != null && !bmp.isRecycled) {
                            val imgBmp = ArtworkResolutionCache.cacheDecodedBitmapAsThumbnail(songId, bmp, songPath)
                            if (imgBmp != null) {
                                resolvedThumbBmp = imgBmp
                            }
                        }
                    }
                },
                onError = {
                    if (!isRetryingArtwork && song != null) {
                        isRetryingArtwork = true
                        val failedSong = song
                        coroutineScope.launch {
                            try {
                                if (resolvedThumbBmp == null) {
                                    ArtworkResolutionCache.invalidateTrack(songId, failedSong.path, uri)
                                }
                                val extracted = withContext(Dispatchers.IO) {
                                    ArtworkResolutionCache.getAuthoritativeArtwork(context, failedSong, isHighResPreferred)
                                }
                                if (latestTrackKey != trackKey) return@launch
                                if (!extracted.isNullOrBlank() && extracted != uri) {
                                    resolvedArtworkUri = extracted
                                    val decoded = withContext(Dispatchers.IO) {
                                        ArtworkResolutionCache.decodeAndCacheThumbnail(context, songId, extracted, failedSong.path, forceReplace = true)
                                    }
                                    if (decoded != null && latestTrackKey == trackKey) {
                                        resolvedThumbBmp = decoded
                                    }
                                } else if (!com.example.util.ManualTagStore.isArtworkManuallyEdited(context, songId, failedSong.path)) {
                                    val online = fetchOnlineArtwork(failedSong)
                                    if (latestTrackKey == trackKey && online != null) {
                                        resolvedArtworkUri = online.first
                                        if (online.second != null) resolvedThumbBmp = online.second
                                    } else if (online == null) {
                                        ArtworkResolutionCache.markArtworkLookupFailed(songId, failedSong.path)
                                        asyncImageLoaded = false
                                        if (resolvedThumbBmp == null) {
                                            resolvedArtworkUri = null
                                        }
                                    }
                                } else {
                                    asyncImageLoaded = false
                                    if (resolvedThumbBmp == null) {
                                        resolvedArtworkUri = null
                                    }
                                }
                            } finally {
                                isRetryingArtwork = false
                            }
                        }
                    } else {
                        asyncImageLoaded = false
                    }
                }
            )
        }
    }
}
