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
        invalidate(songId)
        try {
            com.example.util.ArtworkManager.invalidateNotificationArtwork(songId)
        } catch (_: Throwable) {}
        if (!newArtworkUri.isNullOrBlank()) {
            cache[songId] = newArtworkUri
            manualCoverCache[songId] = newArtworkUri
        }
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
            // Negative cache hit: already verified that no artwork exists on disk
            return null
        }
        if (!mem.isNullOrBlank() && mem.startsWith("file://")) {
            try {
                val path = android.net.Uri.parse(mem).path
                if (path == null || !java.io.File(path).exists() || java.io.File(path).length() == 0L) {
                    cache.remove(songId)
                    return null
                }
            } catch (_: Exception) {}
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
        val manual = getManualCoverUri(context, song.id, song.path)
        if (!manual.isNullOrBlank()) {
            if (song.id > 0L) cache[song.id] = manual
            return manual
        }

        if (song.id > 0L) {
            val persistentFile = java.io.File(java.io.File(context.filesDir, "artworks"), "${song.id}.jpg")
            if (persistentFile.exists() && persistentFile.length() > 0) {
                val uriStr = android.net.Uri.fromFile(persistentFile).toString()
                cache[song.id] = uriStr
                return uriStr
            }
            val persistentSongArt = java.io.File(java.io.File(context.filesDir, "song_artworks"), "${song.id}.jpg")
            if (persistentSongArt.exists() && persistentSongArt.length() > 0) {
                val uriStr = android.net.Uri.fromFile(persistentSongArt).toString()
                cache[song.id] = uriStr
                return uriStr
            }
        }

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

        if (song.id > 0L) {
            val disk = getOrCheckDisk(context, song.id, song.path)
            if (!disk.isNullOrBlank()) return disk
        }

        return null
    }

    fun invalidate(songId: Long) {
        cache.remove(songId)
        manualCoverCache.remove(songId)
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
        if (song == null) return@remember null
        // 1. In-memory cache hit
        val mem = ArtworkResolutionCache.getMemory(songId)
        if (!mem.isNullOrBlank()) return@remember mem

        // 2. Manual cover check from cache/disk
        val manual = ArtworkResolutionCache.getManualCoverUri(context, songId, song.path)
        if (!manual.isNullOrBlank()) {
            ArtworkResolutionCache.cache[songId] = manual
            return@remember manual
        }

        // 3. Valid song artwork URI (not deprecated Android Q albumart table)
        if (!songArtwork.isNullOrBlank() && !songArtwork.contains("audio/albumart")) {
            if (songArtwork.startsWith("file://")) {
                val p = try { android.net.Uri.parse(songArtwork).path } catch (_: Exception) { null }
                if (p != null && java.io.File(p).exists() && java.io.File(p).length() > 0L) {
                    ArtworkResolutionCache.cache[songId] = songArtwork
                    return@remember songArtwork
                }
            } else {
                ArtworkResolutionCache.cache[songId] = songArtwork
                return@remember songArtwork
            }
        }

        // 4. Instant check for already extracted persistent artwork file on disk
        if (songId > 0L) {
            val persistentFile = java.io.File(java.io.File(context.filesDir, "artworks"), "$songId.jpg")
            if (persistentFile.exists() && persistentFile.length() > 0) {
                val uriStr = android.net.Uri.fromFile(persistentFile).toString()
                ArtworkResolutionCache.cache[songId] = uriStr
                return@remember uriStr
            }
            val songArtFile = java.io.File(java.io.File(context.filesDir, "song_artworks"), "$songId.jpg")
            if (songArtFile.exists() && songArtFile.length() > 0) {
                val uriStr = android.net.Uri.fromFile(songArtFile).toString()
                ArtworkResolutionCache.cache[songId] = uriStr
                return@remember uriStr
            }
        }
        null
    }

    var resolvedArtworkUri by remember(songId, songArtwork, isHighResPreferred, isCacheEnabled, artworkVersion) {
        mutableStateOf(initialUri)
    }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(songId, isHighResPreferred, isCacheEnabled, songArtwork, song?.path, artworkVersion) {
        if (song == null) {
            resolvedArtworkUri = null
            return@LaunchedEffect
        }

        // Fast-path: if already resolved and valid, and high-res online fetch is not forced, skip disk I/O
        if (!resolvedArtworkUri.isNullOrBlank() && !forceHighResOnline) {
            return@LaunchedEffect
        }

        // 1. Check manual cover in song_covers first (absolute highest priority, cannot be overridden)
        val manualCover = withContext(Dispatchers.IO) {
            ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
        }
        if (!manualCover.isNullOrBlank()) {
            resolvedArtworkUri = manualCover
            if (isCacheEnabled) {
                ArtworkResolutionCache.cache[songId] = manualCover
            }
            return@LaunchedEffect
        }

        // 2. Direct artwork URI (custom or updated via Tag Editor)
        if (!song.artworkUri.isNullOrBlank()) {
            val isDeprecatedAlbumArt = song.artworkUri.contains("audio/albumart") && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
            if (!isDeprecatedAlbumArt) {
                val fileValid = if (song.artworkUri.startsWith("file://")) {
                    try {
                        val p = android.net.Uri.parse(song.artworkUri).path
                        p != null && java.io.File(p).exists() && java.io.File(p).length() > 0L
                    } catch (_: Exception) { false }
                } else true

                if (fileValid) {
                    resolvedArtworkUri = song.artworkUri
                    if (isCacheEnabled) {
                        ArtworkResolutionCache.cache[songId] = song.artworkUri
                    }
                    if (!forceHighResOnline) {
                        return@LaunchedEffect
                    }
                }
            }
        }

        // 3. Fast memory or disk cache hit if caching enabled and not forcing fresh refresh
        if (isCacheEnabled && !forceHighResOnline) {
            val cachedUri = withContext(Dispatchers.IO) {
                ArtworkResolutionCache.getOrCheckDisk(context, songId, song.path)
            }
            if (!cachedUri.isNullOrBlank()) {
                resolvedArtworkUri = cachedUri
                return@LaunchedEffect
            }
        }

        // 4. Extract local artwork (embedded ID3 APIC / MediaStore thumbnail / folder art)
        if (resolvedArtworkUri.isNullOrBlank()) {
            val localUri = withContext(Dispatchers.IO) {
                ArtworkManager.getOrExtractArtworkUri(
                    context = context,
                    songId = song.id,
                    filePath = song.path,
                    albumId = song.albumId,
                    albumName = song.displayAlbum
                )
            }
            if (!localUri.isNullOrBlank()) {
                resolvedArtworkUri = localUri
                if (isCacheEnabled) {
                    ArtworkResolutionCache.cache[songId] = localUri
                }
                val activeSong = com.example.service.PlaybackManager.activeInstance?.playbackState?.value?.currentSong
                if (activeSong != null && activeSong.id == song.id) {
                    com.example.service.PlaybackManager.activeInstance?.updateCurrentSongArtwork(song.id, localUri)
                }
                // Persist extracted artwork to database so subsequent queries have artworkUri immediately
                withContext(Dispatchers.IO) {
                    try {
                        com.example.data.local.AppDatabase.getInstance(context).songDao().updateSongArtwork(song.id, localUri)
                    } catch (_: Exception) {}
                }
                if (!forceHighResOnline) {
                    return@LaunchedEffect
                }
            }
        }

        // 5. Only fetch online artwork if:
        // - Song genuinely has NO artwork anywhere (resolvedArtworkUri is still null), OR
        // - High-Res online artwork is preferred (which will upgrade the artwork in background without leaving screen blank)
        if (resolvedArtworkUri.isNullOrBlank() || isHighResPreferred || forceHighResOnline) {
            withContext(Dispatchers.IO) {
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
                    withContext(Dispatchers.Main) {
                        resolvedArtworkUri = finalUri
                        val activeSong = com.example.service.PlaybackManager.activeInstance?.playbackState?.value?.currentSong
                        if (activeSong != null && activeSong.id == song.id) {
                            com.example.service.PlaybackManager.activeInstance?.updateCurrentSongArtwork(song.id, finalUri)
                        }
                    }
                }
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
            val imageReq = remember(uri, cachePolicy, cornerRadius, context, artworkVersion) {
                ImageRequest.Builder(context)
                    .data(uri)
                    .memoryCacheKey("${uri}_v$artworkVersion")
                    .diskCacheKey("${uri}_v$artworkVersion")
                    .crossfade(if (isThumbnail) 0 else 120)
                    .memoryCachePolicy(cachePolicy)
                    .diskCachePolicy(cachePolicy)
                    .allowHardware(true)
                    .apply {
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
                    ArtworkResolutionCache.invalidate(songId)
                    coroutineScope.launch(Dispatchers.IO) {
                        val reExtracted = ArtworkManager.getOrExtractArtworkUri(
                            context,
                            songId,
                            song?.path ?: "",
                            song?.albumId ?: 0L,
                            song?.displayAlbum ?: ""
                        )
                        if (!reExtracted.isNullOrBlank()) {
                            ArtworkResolutionCache.cache[songId] = reExtracted
                            withContext(Dispatchers.Main) {
                                resolvedArtworkUri = reExtracted
                            }
                            val activeSong = com.example.service.PlaybackManager.activeInstance?.playbackState?.value?.currentSong
                            if (activeSong != null && activeSong.id == songId) {
                                com.example.service.PlaybackManager.activeInstance?.updateCurrentSongArtwork(songId, reExtracted)
                            }
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
