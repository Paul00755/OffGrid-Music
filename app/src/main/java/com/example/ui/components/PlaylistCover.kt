package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.AppDatabase
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

@Composable
fun PlaylistCover(
    playlist: Playlist,
    playlistSongs: List<Song> = emptyList(),
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12
) {
    val context = LocalContext.current
    val customArtUri = playlist.artworkUri?.ifBlank { null }

    // If playlistSongs passed in is empty and no customArtUri, asynchronously query DB for songs
    var fetchedSongs by remember(playlist.id) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(playlist.id, customArtUri, playlistSongs.size) {
        if (customArtUri == null && playlistSongs.isEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getInstance(context)
                    val entities = db.playlistDao().getPlaylistSongs(playlist.id).firstOrNull() ?: emptyList()
                    val arts = entities.mapNotNull {
                        it.artworkUri?.ifBlank { null }
                            ?: com.example.ui.components.ArtworkResolutionCache.cache[it.id]
                            ?: com.example.util.ArtworkManager.getOrExtractArtworkUri(context, it.id, it.path, it.albumId)
                    }.distinct()
                    withContext(Dispatchers.Main) {
                        fetchedSongs = arts
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val songArtworks = remember(playlistSongs, fetchedSongs) {
        if (playlistSongs.isNotEmpty()) {
            playlistSongs.mapNotNull { song ->
                song.artworkUri?.ifBlank { null }
                    ?: song.contentUriString.takeIf { it.startsWith("http") }
                    ?: com.example.ui.components.ArtworkResolutionCache.cache[song.id]
            }.distinct()
        } else {
            fetchedSongs
        }
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
            // 1. User-selected custom playlist artwork
            customArtUri != null -> {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(customArtUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 2. 4-art collage if 4 or more unique song artwork URIs exist
            songArtworks.size >= 4 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f)) {
                        AsyncImage(
                            model = songArtworks[0],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        AsyncImage(
                            model = songArtworks[1],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    Row(modifier = Modifier.weight(1f)) {
                        AsyncImage(
                            model = songArtworks[2],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        AsyncImage(
                            model = songArtworks[3],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
            // 3. First song's album artwork if 1-3 exist
            songArtworks.isNotEmpty() -> {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(songArtworks.first())
                        .crossfade(true)
                        .build(),
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
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
