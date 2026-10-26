package com.example.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.data.local.AppDatabase
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

object PlaylistCoverCache {
    private const val PREFS_NAME = "playlist_cover_collage_cache"
    private val customCoverMemoryCache = ConcurrentHashMap<Long, String>()
    private val collageMemoryCache = ConcurrentHashMap<Long, List<String>>()

    private val decodedBitmapCache = object : LruCache<String, ImageBitmap>(64) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    @Volatile
    private var prefsLoaded = false

    private fun ensurePrefsLoaded(context: Context) {
        if (prefsLoaded) return
        synchronized(this) {
            if (prefsLoaded) return
            try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                for ((k, v) in prefs.all) {
                    val id = k.removePrefix("collage_").toLongOrNull() ?: continue
                    val raw = v as? String ?: continue
                    val list = raw.split("|").filter { it.isNotBlank() }
                    if (list.isNotEmpty() && !collageMemoryCache.containsKey(id)) {
                        collageMemoryCache[id] = list
                    }
                }
            } catch (_: Exception) {}
            prefsLoaded = true
        }
    }

    fun getDecodedBitmap(uriStr: String): ImageBitmap? = synchronized(decodedBitmapCache) {
        decodedBitmapCache.get(uriStr)
    }

    fun decodeAndCacheBitmapSync(context: Context, uriStr: String, targetPx: Int = 180): ImageBitmap? {
        if (uriStr.isBlank()) return null
        getDecodedBitmap(uriStr)?.let { return it }
        return try {
            val uri = Uri.parse(uriStr)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (uri.scheme == "file" && uri.path != null) {
                val f = File(uri.path!!)
                if (!f.exists() || f.length() <= 0L) return null
                BitmapFactory.decodeFile(f.absolutePath, boundsOpts)
            } else {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOpts)
                }
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
            var sample = 1
            while (boundsOpts.outWidth / (sample * 2) >= targetPx && boundsOpts.outHeight / (sample * 2) >= targetPx) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val bmp = if (uri.scheme == "file" && uri.path != null) {
                BitmapFactory.decodeFile(uri.path!!, decodeOpts)
            } else {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                }
            }
            if (bmp != null) {
                val imgBmp = bmp.asImageBitmap()
                synchronized(decodedBitmapCache) {
                    decodedBitmapCache.put(uriStr, imgBmp)
                }
                imgBmp
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun resolveCustomCoverSync(context: Context, playlistId: Long, artworkUri: String?): String? {
        if (!artworkUri.isNullOrBlank()) {
            if (artworkUri.startsWith("file://")) {
                val path = Uri.parse(artworkUri).path
                if (path != null && File(path).exists() && File(path).length() > 0L) {
                    customCoverMemoryCache[playlistId] = artworkUri
                    return artworkUri
                }
            } else {
                customCoverMemoryCache[playlistId] = artworkUri
                return artworkUri
            }
        }
        // Fallback check on disk in filesDir/playlist_covers if artworkUri pointed to an older timestamped file
        if (!artworkUri.isNullOrBlank() && playlistId > 0L) {
            try {
                val coversDir = File(context.filesDir, "playlist_covers")
                if (coversDir.exists()) {
                    val stable = File(coversDir, "playlist_$playlistId.jpg")
                    val latest = if (stable.exists() && stable.length() > 0L) {
                        stable
                    } else {
                        coversDir.listFiles { f ->
                            f.name.startsWith("playlist_${playlistId}_") || f.name == "playlist_$playlistId.jpg"
                        }?.maxByOrNull { it.lastModified() }
                    }
                    if (latest != null && latest.exists() && latest.length() > 0L) {
                        val resolved = Uri.fromFile(latest).toString()
                        customCoverMemoryCache[playlistId] = resolved
                        return resolved
                    }
                }
            } catch (_: Exception) {}
        }
        if (artworkUri.isNullOrBlank()) {
            customCoverMemoryCache.remove(playlistId)
        }
        return null
    }

    fun getCachedCollage(context: Context, playlistId: Long): List<String>? {
        ensurePrefsLoaded(context)
        return collageMemoryCache[playlistId]
    }

    fun putCachedCollage(context: Context, playlistId: Long, uris: List<String>) {
        if (playlistId > 0L && uris.isNotEmpty()) {
            val prev = collageMemoryCache.put(playlistId, uris)
            if (prev != uris) {
                try {
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString("collage_$playlistId", uris.joinToString("|"))
                        .apply()
                } catch (_: Exception) {}
            }
        }
    }
}

@Composable
private fun StableArtworkImage(
    uriStr: String,
    contentDescription: String?,
    targetPx: Int,
    cacheKeyPrefix: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val initialBitmap = remember(uriStr) {
        PlaylistCoverCache.getDecodedBitmap(uriStr)
            ?: if (uriStr.startsWith("file://")) {
                PlaylistCoverCache.decodeAndCacheBitmapSync(context, uriStr, targetPx)
            } else null
    }
    var loadedBitmap by remember(uriStr) { mutableStateOf(initialBitmap) }

    LaunchedEffect(uriStr) {
        if (loadedBitmap == null) {
            val decoded = withContext(Dispatchers.IO) {
                PlaylistCoverCache.decodeAndCacheBitmapSync(context, uriStr, targetPx)
            }
            if (decoded != null) {
                loadedBitmap = decoded
            }
        }
    }

    val activeBitmap = initialBitmap ?: loadedBitmap
    if (activeBitmap != null) {
        Image(
            bitmap = activeBitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        val req = remember(uriStr, context) {
            val key = "${cacheKeyPrefix}_${uriStr.hashCode()}"
            ImageRequest.Builder(context)
                .data(uriStr)
                .memoryCacheKey(key)
                .diskCacheKey(key)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .allowHardware(true)
                .size(targetPx, targetPx)
                .build()
        }
        AsyncImage(
            model = req,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

@Composable
fun PlaylistCover(
    playlist: Playlist,
    playlistSongs: List<Song> = emptyList(),
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12
) {
    val context = LocalContext.current
    val customArtUri = remember(playlist.id, playlist.artworkUri) {
        PlaylistCoverCache.resolveCustomCoverSync(context, playlist.id, playlist.artworkUri)
    }

    // Synchronous initial song artworks from memory/SharedPreferences/disk cache so playlists never flash
    val initialSongArtworks = remember(playlist.id, playlistSongs) {
        if (playlistSongs.isNotEmpty()) {
            val resolved = playlistSongs.take(12).mapNotNull { song ->
                ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
                    ?: song.artworkUri?.takeIf { it.isNotBlank() && !it.contains("audio/albumart") }
                    ?: song.contentUriString.takeIf { it.startsWith("http") }
            }.distinct().take(4)
            if (resolved.isNotEmpty()) {
                PlaylistCoverCache.putCachedCollage(context, playlist.id, resolved)
            }
            resolved
        } else {
            PlaylistCoverCache.getCachedCollage(context, playlist.id) ?: emptyList()
        }
    }

    var fetchedSongs by remember(playlist.id) {
        mutableStateOf(initialSongArtworks)
    }

    LaunchedEffect(playlist.id, customArtUri, playlistSongs) {
        if (customArtUri == null) {
            if (playlistSongs.isNotEmpty()) {
                val resolved = withContext(Dispatchers.IO) {
                    val list = playlistSongs.take(12).mapNotNull { song ->
                        ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
                            ?: song.artworkUri?.takeIf { it.isNotBlank() && !it.contains("audio/albumart") }
                            ?: com.example.util.ArtworkManager.getOrExtractArtworkUri(context, song.id, song.path, song.albumId)
                    }.distinct().take(4)
                    list.forEach { uri -> PlaylistCoverCache.decodeAndCacheBitmapSync(context, uri, 180) }
                    list
                }
                if (resolved.isNotEmpty()) {
                    PlaylistCoverCache.putCachedCollage(context, playlist.id, resolved)
                    if (fetchedSongs != resolved) {
                        fetchedSongs = resolved
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        val db = AppDatabase.getInstance(context)
                        val entities = db.playlistDao().getPlaylistSongs(playlist.id).firstOrNull() ?: emptyList()
                        val arts = entities.take(12).mapNotNull {
                            ArtworkResolutionCache.getOrCheckDisk(context, it.id, it.path)
                                ?: it.artworkUri?.takeIf { u -> u.isNotBlank() && !u.contains("audio/albumart") }
                                ?: com.example.util.ArtworkManager.getOrExtractArtworkUri(context, it.id, it.path, it.albumId)
                        }.distinct().take(4)
                        arts.forEach { uri -> PlaylistCoverCache.decodeAndCacheBitmapSync(context, uri, 180) }
                        if (arts.isNotEmpty()) {
                            PlaylistCoverCache.putCachedCollage(context, playlist.id, arts)
                            withContext(Dispatchers.Main) {
                                if (fetchedSongs != arts) {
                                    fetchedSongs = arts
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    val songArtworks = if (fetchedSongs.size >= initialSongArtworks.size && fetchedSongs.isNotEmpty()) {
        fetchedSongs
    } else {
        initialSongArtworks
    }

    val shape = RoundedCornerShape(cornerRadius.dp)

    // Deterministic gradient colors based on playlist name
    val gradientColors = remember(playlist.name) {
        val hash = playlist.name.hashCode().absoluteValue
        val palettes = listOf(
            listOf(Color(0xFF6200EA), Color(0xFF9C27B0), Color(0xFFE040FB)),
            listOf(Color(0xFF0091EA), Color(0xFF00B0FF), Color(0xFF80D8FF)),
            listOf(Color(0xFFFF6D00), Color(0xFFFF9100), Color(0xFFFFD180)),
            listOf(Color(0xFF00C853), Color(0xFF64DD17), Color(0xFFAEEA00)),
            listOf(Color(0xFFD500F9), Color(0xFFFF4081), Color(0xFFFF80AB)),
            listOf(Color(0xFF2962FF), Color(0xFF7C4DFF), Color(0xFFB388FF)),
            listOf(Color(0xFFC51162), Color(0xFFFF1744), Color(0xFFFF5252)),
            listOf(Color(0xFF00BFA5), Color(0xFF1DE9B6), Color(0xFFA7FFEB))
        )
        palettes[hash % palettes.size]
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(BentoCardPurple),
        contentAlignment = Alignment.Center
    ) {
        when {
            // 1. User-selected custom playlist artwork (instant synchronous decoded bitmap, zero flicker)
            customArtUri != null -> {
                StableArtworkImage(
                    uriStr = customArtUri,
                    contentDescription = playlist.name,
                    targetPx = 320,
                    cacheKeyPrefix = "pl_cover_${playlist.id}",
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 2. 4-art collage if 4 or more unique song artwork URIs exist
            songArtworks.size >= 4 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f)) {
                        for (i in 0..1) {
                            StableArtworkImage(
                                uriStr = songArtworks[i],
                                contentDescription = null,
                                targetPx = 180,
                                cacheKeyPrefix = "pl_cell",
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                    Row(modifier = Modifier.weight(1f)) {
                        for (i in 2..3) {
                            StableArtworkImage(
                                uriStr = songArtworks[i],
                                contentDescription = null,
                                targetPx = 180,
                                cacheKeyPrefix = "pl_cell",
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
            // 3. First song's album artwork if 1-3 exist
            songArtworks.isNotEmpty() -> {
                StableArtworkImage(
                    uriStr = songArtworks.first(),
                    contentDescription = playlist.name,
                    targetPx = 320,
                    cacheKeyPrefix = "pl_single",
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 4. Default dynamic generative gradient cover with playlist initial
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(gradientColors)),
                    contentAlignment = Alignment.Center
                ) {
                    val initial = playlist.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
                    if (initial.isNotBlank()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = initial,
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp
                            )
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize(0.45f)
                        )
                    }
                }
            }
        }
    }
}