package com.example.ui.components

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// Global high-speed in-memory & disk resolution cache for ultra-smooth scrolling
object ArtworkResolutionCache {
    private val lruCache = object : android.util.LruCache<Long, String>(600) {}
    private val manualCoverCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val manualPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    val artworkUpdateSignal = androidx.compose.runtime.mutableLongStateOf(0L)

    fun notifyArtworkChanged(songId: Long, newArtworkUri: String? = null) {
        if (!newArtworkUri.isNullOrBlank()) {
            cache[songId] = newArtworkUri
            manualCoverCache[songId] = newArtworkUri
        } else {
            cache.remove(songId)
            manualCoverCache.remove(songId)
        }
        manualPathCache.clear()
        try {
            com.example.util.ArtworkManager.invalidateNotificationArtwork(songId)
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
    
    fun getMemory(songId: Long): String? {
        if (songId == 0L) return null
        val mem = cache[songId]
        if (mem != null && mem.isEmpty()) {
            // Negative cache hit: already verified that no artwork exists
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
            val coversDir = java.io.File(context.filesDir, "song_covers")
            if (coversDir.exists()) {
                if (songId != 0L) {
                    val manualCover = coversDir.listFiles { file ->
                        file.name.startsWith("song_${songId}_") || file.name == "song_$songId.jpg"
                    }?.maxByOrNull { it.lastModified() }
                    if (manualCover != null && manualCover.exists() && manualCover.length() > 0) {
                        val uriStr = android.net.Uri.fromFile(manualCover).toString()
                        manualCoverCache[songId] = uriStr
                        return uriStr
                    } else {
                        manualCoverCache[songId] = ""
                    }
                }
                if (!path.isNullOrBlank()) {
                    val pathHash = path.trim().lowercase().hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL
                    val pathCover = java.io.File(coversDir, "song_path_${pathHash}.jpg")
                    if (pathCover.exists() && pathCover.length() > 0) {
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
            // Check manual song_covers first (highest priority)
            val manual = getManualCoverUri(context, songId, path)
            if (!manual.isNullOrBlank()) {
                if (songId != 0L) cache[songId] = manual
                return manual
            }

            // Check device-level .thumbnail file
            if (!path.isNullOrBlank()) {
                val thumbFile = com.example.util.ArtworkManager.findThumbnailArtwork(path)
                if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0L) {
                    val uriStr = android.net.Uri.fromFile(thumbFile).toString()
                    if (songId != 0L) cache[songId] = uriStr
                    return uriStr
                }
            }

            if (songId != 0L) {
                // Check persistent filesDir first
                val persistentFile = java.io.File(java.io.File(context.filesDir, "artworks"), "$songId.jpg")
                if (persistentFile.exists() && persistentFile.length() > 0) {
                    val uriStr = android.net.Uri.fromFile(persistentFile).toString()
                    cache[songId] = uriStr
                    return uriStr
                }
                val persistentSongArt = java.io.File(java.io.File(context.filesDir, "song_artworks"), "$songId.jpg")
                if (persistentSongArt.exists() && persistentSongArt.length() > 0) {
                    val uriStr = android.net.Uri.fromFile(persistentSongArt).toString()
                    cache[songId] = uriStr
                    return uriStr
                }
                // Check cacheDir
                val cacheFile = java.io.File(java.io.File(context.cacheDir, "artworks"), "$songId.jpg")
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    val uriStr = android.net.Uri.fromFile(cacheFile).toString()
                    cache[songId] = uriStr
                    return uriStr
                }
                val songArtFile = java.io.File(java.io.File(context.cacheDir, "song_artworks"), "$songId.jpg")
                if (songArtFile.exists() && songArtFile.length() > 0) {
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
        } catch (_: Exception) {}
        return null
    }

    fun isManualCover(context: Context, songId: Long, path: String? = null): Boolean {
        return !getManualCoverUri(context, songId, path).isNullOrBlank()
    }

    fun hasSuccessfullyFetchedOnlineArtwork(context: Context, songId: Long): Boolean {
        if (songId <= 0L) return false
        try {
            val persistentFile = java.io.File(java.io.File(context.filesDir, "artworks"), "$songId.jpg")
            if (persistentFile.exists() && persistentFile.length() > 0) return true
            val onlinePref = context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
            return !onlinePref.getString(songId.toString(), null).isNullOrBlank()
        } catch (_: Exception) {
            return false
        }
    }

    fun getAuthoritativeArtwork(context: Context, song: com.example.data.model.Song, isHighResPreferred: Boolean = false): String? {
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

        // 3. True embedded picture from song's actual audio file (Guarantees track-specific art)
        if (song.path.isNotBlank()) {
            val f = java.io.File(song.path)
            if (f.exists() && f.length() > 1024L) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(song.path)
                    val artBytes = mmr.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        val persistentDir = java.io.File(context.filesDir, "artworks")
                        if (!persistentDir.exists()) persistentDir.mkdirs()
                        val persistentFile = java.io.File(persistentDir, "${song.id}.jpg")
                        val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, boundsOpts)
                        val sampleSize = com.example.util.ArtworkManager.calculateInSampleSize(boundsOpts, 512, 512)
                        val opts = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        }
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)
                        if (bmp != null) {
                            java.io.FileOutputStream(persistentFile).use { out ->
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            bmp.recycle()
                            val uriStr = android.net.Uri.fromFile(persistentFile).toString()
                            if (song.id > 0L) cache[song.id] = uriStr
                            return uriStr
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { mmr.release() } catch (_: Throwable) {}
                }
            }
        }

        // 4. Check song-specific local companion file (.jpg named after the song)
        if (song.path.isNotBlank()) {
            val thumbFile = com.example.util.ArtworkManager.findThumbnailArtwork(song.path)
            if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0L) {
                val uriStr = android.net.Uri.fromFile(thumbFile).toString()
                if (song.id > 0L) cache[song.id] = uriStr
                return uriStr
            }
        }

        // 5. Existing validated song.artworkUri
        if (!song.artworkUri.isNullOrBlank()) {
            if (song.artworkUri.startsWith("file://")) {
                val p = try { android.net.Uri.parse(song.artworkUri).path } catch (_: Exception) { null }
                if (p != null && java.io.File(p).exists() && java.io.File(p).length() > 0L) {
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

        // 6. Check persistent filesDir / cache
        if (song.id > 0L) {
            val persistentFile = java.io.File(java.io.File(context.filesDir, "artworks"), "${song.id}.jpg")
            if (persistentFile.exists() && persistentFile.length() > 0) {
                val uriStr = android.net.Uri.fromFile(persistentFile).toString()
                cache[song.id] = uriStr
                return uriStr
            }
        }

        return null
    }

    fun invalidate(songId: Long) {
        cache.remove(songId)
        manualCoverCache.remove(songId)
        manualPathCache.clear()
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
    val initialUri = remember(songId, songArtwork, artworkVersion) {
        if (song == null || songId == 0L) return@remember null
        // 1. Instant pure in-memory cache hit first (zero disk I/O on UI thread)
        val mem = ArtworkResolutionCache.getMemory(songId)
        if (!mem.isNullOrBlank()) return@remember mem
        if (mem != null && mem.isEmpty()) return@remember null

        // 2. Direct song model artwork if present and not deprecated MediaStore albumart
        if (!songArtwork.isNullOrBlank() && !songArtwork.contains("audio/albumart")) {
            return@remember songArtwork
        }
        null
    }

    var resolvedArtworkUri by remember(songId) {
        mutableStateOf(initialUri)
    }

    // Keep resolvedArtworkUri updated if initialUri resolves non-null
    LaunchedEffect(initialUri) {
        if (!initialUri.isNullOrBlank() && (resolvedArtworkUri.isNullOrBlank() || resolvedArtworkUri != initialUri)) {
            resolvedArtworkUri = initialUri
        }
    }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(songId, isHighResPreferred, isCacheEnabled, songArtwork, song?.path, artworkVersion) {
        if (song == null || songId <= 0L) {
            resolvedArtworkUri = null
            return@LaunchedEffect
        }

        // Fast-path: if already resolved and valid, and high-res online fetch is not forced, skip disk I/O entirely!
        if (!resolvedArtworkUri.isNullOrBlank() && !forceHighResOnline) {
            return@LaunchedEffect
        }

        // Debounce slightly during fast scrolling so that quickly scrolled-past items don't saturate I/O
        kotlinx.coroutines.delay(20)

        // All disk and network operations run exclusively on Dispatchers.IO
        val resolved = withContext(Dispatchers.IO) {
            // 1. Authoritative check across manual covers, disk, and persistent files
            val authoritative = ArtworkResolutionCache.getAuthoritativeArtwork(context, song)
            if (!authoritative.isNullOrBlank()) {
                ArtworkResolutionCache.cache[songId] = authoritative
                return@withContext authoritative
            }

            // 2. Check manual cover in song_covers
            val manualCover = ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
            if (!manualCover.isNullOrBlank()) {
                ArtworkResolutionCache.cache[songId] = manualCover
                return@withContext manualCover
            }

            // 3. Extract local artwork (embedded ID3 APIC / folder art)
            val localUri = ArtworkManager.getOrExtractArtworkUri(
                context = context,
                songId = song.id,
                filePath = song.path,
                albumId = song.albumId,
                albumName = song.displayAlbum
            )
            if (!localUri.isNullOrBlank()) {
                if (isCacheEnabled) {
                    ArtworkResolutionCache.cache[songId] = localUri
                }
                if (!song.isManuallyEdited) {
                    try {
                        com.example.data.local.AppDatabase.getInstance(context).songDao().updateSongArtwork(song.id, localUri)
                    } catch (_: Exception) {}
                }
                return@withContext localUri
            }

            // 4. Try online search if genuinely NO artwork found!
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
                    ArtworkResolutionCache.cache[songId] = finalUri
                }
                return@withContext finalUri
            } else {
                ArtworkResolutionCache.cache[songId] = ""
                null
            }
        }

        if (!resolved.isNullOrBlank()) {
            resolvedArtworkUri = resolved
            val activeSong = com.example.service.PlaybackManager.activeInstance?.playbackState?.value?.currentSong
            if (activeSong != null && activeSong.id == song.id) {
                com.example.service.PlaybackManager.activeInstance?.updateCurrentSongArtwork(song.id, resolved)
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
        if (!uri.isNullOrBlank()) {
            val cachePolicy = if (isCacheEnabled) coil.request.CachePolicy.ENABLED else coil.request.CachePolicy.DISABLED
            val isThumbnail = cornerRadius <= 14
            val cachedBmp = remember(songId) {
                ArtworkManager.getCachedNotificationBitmap(songId)
            }
            val imageReq = remember(uri, cachePolicy, cornerRadius, context) {
                ImageRequest.Builder(context)
                    .data(uri)
                    .memoryCacheKey("art_${songId}_${uri.hashCode()}")
                    .diskCacheKey("art_${songId}_${uri.hashCode()}")
                    .crossfade(false)
                    .memoryCachePolicy(cachePolicy)
                    .diskCachePolicy(cachePolicy)
                    .allowHardware(true)
                    .apply {
                        if (cachedBmp != null && !cachedBmp.isRecycled) {
                            placeholder(android.graphics.drawable.BitmapDrawable(context.resources, cachedBmp))
                            error(android.graphics.drawable.BitmapDrawable(context.resources, cachedBmp))
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
                        val auth = song?.let { ArtworkResolutionCache.getAuthoritativeArtwork(context, it) }
                        val reExtracted = if (!auth.isNullOrBlank()) auth else {
                            ArtworkManager.getOrExtractArtworkUri(
                                context,
                                songId,
                                song?.path ?: "",
                                song?.albumId ?: 0L,
                                song?.displayAlbum ?: ""
                            )
                        }
                        withContext(Dispatchers.Main) {
                            if (!reExtracted.isNullOrBlank()) {
                                ArtworkResolutionCache.cache[songId] = reExtracted
                                resolvedArtworkUri = reExtracted
                                val activeSong = com.example.service.PlaybackManager.activeInstance?.playbackState?.value?.currentSong
                                if (activeSong != null && activeSong.id == songId) {
                                    com.example.service.PlaybackManager.activeInstance?.updateCurrentSongArtwork(songId, reExtracted)
                                }
                            }
                            // Never set resolvedArtworkUri = null on error; preserve current artwork!
                        }
                    }
                }
            )
        } else {
            // Procedural cover with music icon & title
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
