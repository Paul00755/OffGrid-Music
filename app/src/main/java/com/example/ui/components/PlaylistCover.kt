package com.example.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
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
    private val collageSignatureCache = ConcurrentHashMap<Long, String>()
    private val artworkFingerprintCache = ConcurrentHashMap<String, String>()

    private val decodedBitmapCache = object : LruCache<String, ImageBitmap>(64) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    fun getDecodedBitmap(uriStr: String): ImageBitmap? = synchronized(decodedBitmapCache) {
        decodedBitmapCache.get(uriStr)
    }

    internal fun getCollageUris(playlistId: Long): List<String>? = collageMemoryCache[playlistId]

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

    fun getCachedCollage(playlistId: Long, signature: String): List<String>? =
        collageMemoryCache[playlistId]?.takeIf { collageSignatureCache[playlistId] == signature }

    suspend fun loadCachedCollage(context: Context, playlistId: Long, signature: String): List<String>? =
        withContext(Dispatchers.IO) {
            getCachedCollage(playlistId, signature)?.let { return@withContext it }
            try {
                val raw = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("collage_$playlistId", null) ?: return@withContext null
                val lines = raw.split('\n')
                if (lines.firstOrNull() != signature) return@withContext null
                val uris = lines.drop(1).filter(String::isNotBlank)
                collageSignatureCache[playlistId] = signature
                collageMemoryCache[playlistId] = uris
                uris
            } catch (_: Exception) { null }
        }

    fun putCachedCollage(context: Context, playlistId: Long, signature: String, uris: List<String>) {
        if (playlistId <= 0L || signature.isBlank()) return
        collageSignatureCache[playlistId] = signature
        collageMemoryCache[playlistId] = uris
        try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("collage_$playlistId", (listOf(signature) + uris).joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    suspend fun artworkFingerprint(context: Context, uriString: String): String? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        val uri = Uri.parse(uriString)
        val file = if (uri.scheme == "file") uri.path?.let(::File) else null
        val cacheKey = "$uriString|${file?.length() ?: 0L}|${file?.lastModified() ?: 0L}"
        artworkFingerprintCache[cacheKey]?.let { return@withContext it }
        try {
            fun decode(options: BitmapFactory.Options): Bitmap? = if (file != null) {
                if (!file.exists() || file.length() <= 0L) null else BitmapFactory.decodeFile(file.absolutePath, options)
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decode(bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / sample > 64 || bounds.outHeight / sample > 64) sample *= 2
            val bitmap = decode(BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: return@withContext null
            try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                for (y in 0 until 12) for (x in 0 until 12) {
                    val px = bitmap.getPixel(((x + 0.5f) * bitmap.width / 12).toInt().coerceIn(0, bitmap.width - 1),
                        ((y + 0.5f) * bitmap.height / 12).toInt().coerceIn(0, bitmap.height - 1))
                    digest.update((android.graphics.Color.red(px) shr 4).toByte())
                    digest.update((android.graphics.Color.green(px) shr 4).toByte())
                    digest.update((android.graphics.Color.blue(px) shr 4).toByte())
                }
                digest.digest().joinToString("") { "%02x".format(it) }.also {
                    artworkFingerprintCache[cacheKey] = it
                    if (artworkFingerprintCache.size > 2048) {
                        artworkFingerprintCache.keys.firstOrNull()?.let(artworkFingerprintCache::remove)
                    }
                }
            } finally { bitmap.recycle() }
        } catch (_: Exception) { null }
    }
}

private data class PlaylistArtCandidate(
    val id: Long,
    val path: String,
    val artworkUri: String?,
    val contentUri: String,
    val albumId: Long
)

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
    var customArtUri by remember(playlist.id, playlist.artworkUri) {
        mutableStateOf(playlist.artworkUri?.takeIf(String::isNotBlank))
    }
    var fetchedSongs by remember(playlist.id, playlistSongs) { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(playlist.id, playlist.artworkUri) {
        customArtUri = withContext(Dispatchers.IO) {
            PlaylistCoverCache.resolveCustomCoverSync(context, playlist.id, playlist.artworkUri)
        }
    }

    LaunchedEffect(playlist.id, playlistSongs, customArtUri) {
        if (customArtUri != null) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            try {
                val candidates = if (playlistSongs.isNotEmpty()) {
                    playlistSongs.map { song -> PlaylistArtCandidate(song.id, song.path, song.artworkUri, song.contentUriString, song.albumId) }
                } else {
                    val entities = AppDatabase.getInstance(context).playlistDao().getPlaylistSongs(playlist.id).firstOrNull().orEmpty()
                    entities.map { song -> PlaylistArtCandidate(song.id, song.path, song.artworkUri, song.contentUriString, song.albumId) }
                }
                val signatureInput = candidates.joinToString("\u001f") { candidate ->
                    val sourceFile = File(candidate.path)
                    val artFile = candidate.artworkUri?.let { raw -> Uri.parse(raw).path?.let(::File) }
                    "${candidate.id}|${candidate.path}|${sourceFile.length()}|${sourceFile.lastModified()}|${candidate.artworkUri}|${artFile?.length() ?: 0}|${artFile?.lastModified() ?: 0}|${candidate.contentUri}|${ArtworkResolutionCache.getSongArtworkVersion(candidate.id, candidate.path)}"
                }
                val signature = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(signatureInput.toByteArray()).joinToString("") { "%02x".format(it) }
                val cached = PlaylistCoverCache.loadCachedCollage(context, playlist.id, signature)
                if (cached != null) return@withContext cached

                val uniqueArt = LinkedHashMap<String, String>()
                for (candidate in candidates) {
                    val uri = ArtworkResolutionCache.getMemory(candidate.id, candidate.path)
                        ?: ArtworkResolutionCache.getOrCheckDisk(context, candidate.id, candidate.path)
                        ?: candidate.artworkUri?.takeIf { it.isNotBlank() && !it.contains("audio/albumart") }
                        ?: candidate.contentUri.takeIf { it.startsWith("http") }
                        ?: com.example.util.ArtworkManager.getOrExtractArtworkUri(context, candidate.id, candidate.path, candidate.albumId)
                        ?: continue
                    val fingerprint = PlaylistCoverCache.artworkFingerprint(context, uri) ?: "uri:${uri.trim().lowercase()}"
                    uniqueArt.putIfAbsent(fingerprint, uri)
                    if (uniqueArt.size == 4) break
                }
                val selected = uniqueArt.values.toList()
                selected.forEach { uri -> PlaylistCoverCache.decodeAndCacheBitmapSync(context, uri, 180) }
                PlaylistCoverCache.putCachedCollage(context, playlist.id, signature, selected)
                selected
            } catch (_: Exception) { emptyList() }
        }
        fetchedSongs = resolved
    }

    val songArtworks = fetchedSongs
    val displayedCustomArtUri = customArtUri

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
            displayedCustomArtUri != null -> {
                StableArtworkImage(
                    uriStr = displayedCustomArtUri,
                    contentDescription = playlist.name,
                    targetPx = 320,
                    cacheKeyPrefix = "pl_cover_${playlist.id}",
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 2. Four-art collage when enough unique song artwork is available
            songArtworks.size >= 4 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f)) {
                        for (i in 0..1) {
                            StableArtworkImage(
                                uriStr = songArtworks[i],
                                contentDescription = "${playlist.name} cover artwork ${i + 1}",
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
                                contentDescription = "${playlist.name} cover artwork ${i + 1}",
                                targetPx = 180,
                                cacheKeyPrefix = "pl_cell",
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
            // Show all available unique artwork instead of discarding items when fewer than four exist.
            songArtworks.size == 3 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    StableArtworkImage(
                        uriStr = songArtworks[0],
                        contentDescription = "${playlist.name} cover artwork 1",
                        targetPx = 180,
                        cacheKeyPrefix = "pl_cell",
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    Row(modifier = Modifier.weight(1f)) {
                        for (i in 1..2) {
                            StableArtworkImage(
                                uriStr = songArtworks[i],
                                contentDescription = "${playlist.name} cover artwork ${i + 1}",
                                targetPx = 180,
                                cacheKeyPrefix = "pl_cell",
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
            songArtworks.size == 2 -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    songArtworks.forEachIndexed { index, uri ->
                        StableArtworkImage(
                            uriStr = uri,
                            contentDescription = "${playlist.name} cover artwork ${index + 1}",
                            targetPx = 180,
                            cacheKeyPrefix = "pl_cell",
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
            // 3. A single unique image fills the cover.
            songArtworks.size == 1 -> {
                StableArtworkImage(
                    uriStr = songArtworks[0],
                    contentDescription = "${playlist.name} cover artwork 1",
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
