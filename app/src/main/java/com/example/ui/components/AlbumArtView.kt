package com.example.ui.components

import android.content.Context
import android.graphics.drawable.BitmapDrawable
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
    private val lruCache = object : android.util.LruCache<Long, String>(6000) {}
    private val manualCoverCache = ConcurrentHashMap<Long, String>()
    private val manualPathCache = ConcurrentHashMap<String, String>()
    private val preloadedKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val preloaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    val onlineArtworkDispatcher = Dispatchers.IO.limitedParallelism(2)

    @Volatile
    private var activePreloadJob: Job? = null

    val artworkUpdateSignal = mutableLongStateOf(0L)

    fun notifyArtworkChanged(songId: Long, newArtworkUri: String? = null) {
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
        artworkUpdateSignal.longValue = System.currentTimeMillis()
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
     * Proactively resolves artwork and preloads decoded thumbnails into Coil's in-memory cache
     * ahead of the user's viewport. Cancels any stale preload job when the viewport moves so
     * fast scrolling is never bogged down by off-screen work.
     */
    fun preloadWindow(
        context: Context,
        songs: List<Song>,
        firstVisibleIndex: Int,
        windowAhead: Int = 28,
        windowBehind: Int = 8
    ) {
        if (songs.isEmpty()) return
        val safeFirst = firstVisibleIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        val startIndex = (safeFirst - windowBehind).coerceAtLeast(0)
        val endIndex = (safeFirst + windowAhead).coerceAtMost(songs.size)
        if (startIndex >= endIndex) return

        // Prioritize the immediately visible items first (safeFirst..endIndex), then items behind
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
                    if (!uri.isNullOrBlank()) {
                        cache[song.id] = uri
                    }
                }

                if (!uri.isNullOrBlank()) {
                    val cacheKey = "art_${song.id}_${uri.hashCode()}"
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
        if (songId != 0L) {
            manualCoverCache[songId]?.let { return if (it.isEmpty()) null else it }
        }
        if (!path.isNullOrBlank()) {
            manualPathCache[path]?.let { return if (it.isEmpty()) null else it }
        }
        try {
            val coversDir = File(context.filesDir, "song_covers")
            if (coversDir.exists()) {
                if (songId != 0L) {
                    val stableCover = File(coversDir, "song_$songId.jpg")
                    val manualCover = if (ArtworkManager.isValidCompleteImageFile(stableCover)) {
                        stableCover
                    } else {
                        coversDir.listFiles { file ->
                            (file.name.startsWith("song_${songId}_") || file.name == "song_$songId.jpg") &&
                                ArtworkManager.isValidCompleteImageFile(file)
                        }?.maxByOrNull { it.lastModified() }
                    }
                    if (manualCover != null) {
                        val uriStr = android.net.Uri.fromFile(manualCover).toString()
                        manualCoverCache[songId] = uriStr
                        return uriStr
                    } else {
                        manualCoverCache[songId] = ""
                    }
                }
                if (!path.isNullOrBlank()) {
                    val pathHash = path.trim().lowercase().hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL
                    val pathCover = File(coversDir, "song_path_${pathHash}.jpg")
                    if (ArtworkManager.isValidCompleteImageFile(pathCover)) {
                        val uriStr = android.net.Uri.fromFile(pathCover).toString()
                        manualPathCache[path] = uriStr
                        return uriStr
                    } else {
                        manualPathCache[path] = ""
                    }
                }
            } else {
                if (songId != 0L) manualCoverCache[songId] = ""
                if (!path.isNullOrBlank()) manualPathCache[path] = ""
            }
        } catch (_: Exception) {}
        return null
    }

    fun getOrCheckDisk(context: Context, songId: Long, path: String? = null): String? {
        if (songId == 0L && path.isNullOrBlank()) return null
        if (songId != 0L) {
            val mem = cache[songId]
            if (mem != null) {
                return if (mem.isEmpty()) null else mem
            }
        }
        try {
            // 1. Check manual song_covers first (highest priority)
            val manual = getManualCoverUri(context, songId, path)
            if (!manual.isNullOrBlank()) {
                if (songId != 0L) cache[songId] = manual
                return manual
            }

            if (songId != 0L) {
                // 2. Check persistent filesDir/artworks/$songId.jpg (validated complete JPEG)
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
                // 3. Check cacheDir
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

            // 4. Check device-level .thumbnail file
            if (!path.isNullOrBlank()) {
                val thumbFile = ArtworkManager.findThumbnailArtwork(path)
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
            val persistentFile = File(File(context.filesDir, "artworks"), "$songId.jpg")
            if (ArtworkManager.isValidCompleteImageFile(persistentFile)) return true
            val onlinePref = context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
            return !onlinePref.getString(songId.toString(), null).isNullOrBlank()
        } catch (_: Exception) {
            return false
        }
    }

    suspend fun getAuthoritativeArtwork(context: Context, song: Song, isHighResPreferred: Boolean = false): String? {
        if (song.id > 0L) {
            val mem = cache[song.id]
            if (!mem.isNullOrBlank()) return mem
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

        // 5. Extract via ArtworkManager (atomic write + bounded MMR concurrency + MediaStore fallback)
        return ArtworkManager.getOrExtractArtworkUri(
            context = context,
            songId = song.id,
            filePath = song.path,
            albumId = song.albumId,
            albumName = song.displayAlbum
        )
    }

    fun invalidate(songId: Long) {
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
        cache.clear()
        manualCoverCache.clear()
        manualPathCache.clear()
        preloadedKeys.clear()
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

    val songId = song?.id ?: 0L
    val gradientIndex = (songId.toInt() % ARTWORK_GRADIENTS.size).let { if (it < 0) -it else it }
    val gradientColors = ARTWORK_GRADIENTS[gradientIndex]

    val artworkVersion = ArtworkResolutionCache.artworkUpdateSignal.longValue
    val songArtwork = song?.artworkUri?.takeIf { it.isNotBlank() }

    // Synchronously resolve from memory or validated local disk file so artwork appears on Frame 1
    val initialUri = remember(songId, songArtwork, artworkVersion) {
        if (song == null || songId == 0L) return@remember null
        // 1. Instant pure in-memory cache hit
        val mem = ArtworkResolutionCache.getMemory(songId)
        if (!mem.isNullOrBlank()) return@remember mem

        // 2. Fast local persistent disk check (validates complete JPEG file)
        val diskHit = ArtworkResolutionCache.getOrCheckDisk(context, songId, song.path)
        if (!diskHit.isNullOrBlank()) return@remember diskHit

        // 3. Direct song model artwork if present and valid
        if (!songArtwork.isNullOrBlank() && !songArtwork.contains("audio/albumart")) {
            if (songArtwork.startsWith("file://")) {
                val p = try { android.net.Uri.parse(songArtwork).path } catch (_: Exception) { null }
                if (ArtworkManager.isValidCompleteImageFile(p?.let { File(it) })) {
                    ArtworkResolutionCache.cache[songId] = songArtwork
                    return@remember songArtwork
                }
            } else {
                ArtworkResolutionCache.cache[songId] = songArtwork
                return@remember songArtwork
            }
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

    LaunchedEffect(songId, isHighResPreferred, isCacheEnabled, songArtwork, song?.path, artworkVersion) {
        if (song == null || songId <= 0L) {
            resolvedArtworkUri = null
            return@LaunchedEffect
        }

        if (!resolvedArtworkUri.isNullOrBlank() && !forceHighResOnline) {
            return@LaunchedEffect
        }

        // Step 1: Fast local extraction (embedded ID3 / MediaStore thumbnail / companion file)
        val localResolved = withContext(Dispatchers.IO) {
            val authoritative = ArtworkResolutionCache.getAuthoritativeArtwork(context, song)
            if (!authoritative.isNullOrBlank()) {
                ArtworkResolutionCache.cache[songId] = authoritative
                if (!song.isManuallyEdited && song.artworkUri != authoritative) {
                    try {
                        com.example.data.local.AppDatabase.getInstance(context).songDao().updateSongArtwork(song.id, authoritative)
                    } catch (_: Exception) {}
                }
                return@withContext authoritative
            }
            null
        }

        if (!localResolved.isNullOrBlank()) {
            resolvedArtworkUri = localResolved
            val activeSong = com.example.service.PlaybackManager.activeInstance?.playbackState?.value?.currentSong
            if (activeSong != null && activeSong.id == song.id) {
                com.example.service.PlaybackManager.activeInstance?.updateCurrentSongArtwork(song.id, localResolved)
            }
            return@LaunchedEffect
        }

        // Step 2: Online artwork fallback on a dedicated low-concurrency dispatcher so network calls
        // NEVER block local artwork extraction for other songs in the list!
        val onlineResolved = withContext(ArtworkResolutionCache.onlineArtworkDispatcher) {
            val online = OnlineArtistImageFetcher.getSongArtworkUrl(
                context = context,
                title = song.displayTitle,
                artist = song.displayArtist,
                album = song.displayAlbum,
                preferUltraHd = isHighResPreferred,
                cacheEnabled = isCacheEnabled,
                songId = song.id
            )
            if (!online.isNullOrBlank()) {
                val savedLocal = ArtworkManager.saveFetchedArtworkLocally(context, song.id, online)
                val finalUri = savedLocal ?: online
                if (isCacheEnabled) {
                    try {
                        context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                            .edit().putString(song.id.toString(), finalUri).apply()
                    } catch (_: Exception) {}
                }
                ArtworkResolutionCache.cache[songId] = finalUri
                finalUri
            } else {
                ArtworkResolutionCache.cache[songId] = ""
                null
            }
        }

        if (!onlineResolved.isNullOrBlank()) {
            resolvedArtworkUri = onlineResolved
            val activeSong = com.example.service.PlaybackManager.activeInstance?.playbackState?.value?.currentSong
            if (activeSong != null && activeSong.id == song.id) {
                com.example.service.PlaybackManager.activeInstance?.updateCurrentSongArtwork(song.id, onlineResolved)
            }
        }
    }

    val backgroundBrush = remember(gradientIndex) { Brush.linearGradient(gradientColors) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(backgroundBrush),
        contentAlignment = Alignment.Center
    ) {
        val uri = resolvedArtworkUri
        val cachedBmp = remember(songId, artworkVersion) {
            ArtworkManager.getCachedNotificationBitmap(songId)
        }

        if (!uri.isNullOrBlank()) {
            val isThumbnail = cornerRadius <= 14
            val imageReq = remember(uri, cornerRadius, context, artworkVersion) {
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
                            size(180, 180)
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
                        ArtworkManager.invalidateSongArtwork(context, songId, deleteFiles = true)
                        val reExtracted = ArtworkManager.getOrExtractArtworkUri(
                            context,
                            songId,
                            song?.path ?: "",
                            song?.albumId ?: 0L,
                            song?.displayAlbum ?: ""
                        )
                        withContext(Dispatchers.Main) {
                            if (!reExtracted.isNullOrBlank()) {
                                ArtworkResolutionCache.cache[songId] = reExtracted
                                resolvedArtworkUri = reExtracted
                                val activeSong = com.example.service.PlaybackManager.activeInstance?.playbackState?.value?.currentSong
                                if (activeSong != null && activeSong.id == songId) {
                                    com.example.service.PlaybackManager.activeInstance?.updateCurrentSongArtwork(songId, reExtracted)
                                }
                            }
                        }
                    }
                }
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