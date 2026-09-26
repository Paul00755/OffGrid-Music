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
    private val lruCache = object : android.util.LruCache<Long, String>(400) {}

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
        try {
            val coversDir = java.io.File(context.filesDir, "song_covers")
            if (coversDir.exists()) {
                if (songId != 0L) {
                    val manualCover = coversDir.listFiles { file ->
                        file.name.startsWith("song_${songId}_") || file.name == "song_$songId.jpg"
                    }?.maxByOrNull { it.lastModified() }
                    if (manualCover != null && manualCover.exists() && manualCover.length() > 0) {
                        return android.net.Uri.fromFile(manualCover).toString()
                    }
                }
                if (!path.isNullOrBlank()) {
                    val pathHash = path.trim().lowercase().hashCode().toLong() and 0x7FFFFFFF_FFFFFFFFL
                    val pathCover = java.io.File(coversDir, "song_path_${pathHash}.jpg")
                    if (pathCover.exists() && pathCover.length() > 0) {
                        return android.net.Uri.fromFile(pathCover).toString()
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun getOrCheckDisk(context: Context, songId: Long, path: String? = null): String? {
        if (songId == 0L && path.isNullOrBlank()) return null
        if (songId != 0L) {
            getMemory(songId)?.let { return it }
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

    fun invalidate(songId: Long) {
        cache.remove(songId)
    }
    fun clear() {
        cache.clear()
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

    val songArtwork = song?.artworkUri?.takeIf { it.isNotBlank() }
    val initialUri = if (song != null) {
        ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
            ?: songArtwork
            ?: if (isCacheEnabled) ArtworkResolutionCache.getMemory(songId) else null
    } else null

    var resolvedArtworkUri by remember(songId, song?.artworkUri, isHighResPreferred, isCacheEnabled) {
        mutableStateOf(initialUri)
    }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(songId, isHighResPreferred, isCacheEnabled, song?.artworkUri, song?.path) {
        if (song == null) {
            resolvedArtworkUri = null
            return@LaunchedEffect
        }

        // 1. Check manual cover in song_covers first (absolute highest priority, cannot be overridden by online artwork)
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

        // 2. If the song has a direct artwork URI (custom or updated via Tag Editor), verify file exists before using!
        if (!song.artworkUri.isNullOrBlank()) {
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
                return@LaunchedEffect
            }
        }

        if (resolvedArtworkUri != null && !forceHighResOnline) {
            return@LaunchedEffect
        }

        // Fast memory or disk cache hit if caching enabled and not forcing fresh refresh
        if (isCacheEnabled && !forceHighResOnline) {
            val cachedUri = withContext(Dispatchers.IO) {
                ArtworkResolutionCache.getOrCheckDisk(context, songId, song.path)
            }
            if (!cachedUri.isNullOrBlank()) {
                resolvedArtworkUri = cachedUri
                return@LaunchedEffect
            }
        }

        withContext(Dispatchers.IO) {
            var finalUri: String? = null

            // Strategy 1: If "Prefer High-Res Online Artwork" is enabled:
            // Fetch Ultra-HD covers (up to 1920x1080) from iTunes/Deezer
            if (isHighResPreferred) {
                val online = OnlineArtistImageFetcher.getSongArtworkUrl(
                    context = context,
                    title = song.displayTitle,
                    artist = song.displayArtist,
                    album = song.displayAlbum,
                    preferUltraHd = true,
                    cacheEnabled = isCacheEnabled,
                    songId = song.id
                )
                if (!online.isNullOrBlank()) {
                    finalUri = online
                    if (isCacheEnabled) {
                        try {
                            context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                                .edit().putString(songId.toString(), online).apply()
                        } catch (_: Exception) {}
                    }
                }
            }

            // Strategy 2: Check local image (artworkUri or embedded ID3 APIC)
            if (finalUri.isNullOrBlank()) {
                val localUri = song.artworkUri
                if (!localUri.isNullOrBlank() && (localUri.startsWith("file://") || localUri.startsWith("content://"))) {
                    finalUri = localUri
                } else {
                    val extracted = ArtworkManager.getOrExtractArtworkUri(
                        context = context,
                        songId = song.id,
                        filePath = song.path,
                        albumId = song.albumId,
                        albumName = song.displayAlbum
                    )
                    if (!extracted.isNullOrBlank()) {
                        finalUri = extracted
                    }
                }
            }

            // If Fetch Hi-Res Artwork = OFF, do NOT fetch from network.
            // Check only existing cached online art without issuing new network requests.
            if (finalUri.isNullOrBlank() && !isHighResPreferred && isCacheEnabled) {
                val existingCached = try {
                    context.getSharedPreferences("song_artworks_online_cache_by_id", Context.MODE_PRIVATE)
                        .getString(songId.toString(), null)
                } catch (_: Exception) { null }
                if (!existingCached.isNullOrBlank()) {
                    finalUri = existingCached
                }
            }

            if (!finalUri.isNullOrBlank()) {
                if (isCacheEnabled) {
                    ArtworkResolutionCache.cache[songId] = finalUri
                }
                withContext(Dispatchers.Main) {
                    resolvedArtworkUri = finalUri
                }
            } else {
                // If not found and had no cached uri, set to null, otherwise keep previous
                if (resolvedArtworkUri == null) {
                    withContext(Dispatchers.Main) {
                        resolvedArtworkUri = null
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
            val imageReq = remember(uri, cachePolicy, cornerRadius, context) {
                ImageRequest.Builder(context)
                    .data(uri)
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
                            if (reExtracted != resolvedArtworkUri) {
                                withContext(Dispatchers.Main) {
                                    resolvedArtworkUri = reExtracted
                                }
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
