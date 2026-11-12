package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
fun RemoveFromPlaylistDialog(
    song: Song,
    onGetPlaylistsContainingSong: suspend (Long) -> List<Playlist>,
    onRemoveFromPlaylist: (playlistId: Long, songId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var playlistToConfirm by remember { mutableStateOf<Playlist?>(null) }

    LaunchedEffect(song.id) {
        isLoading = true
        playlists = onGetPlaylistsContainingSong(song.id)
        isLoading = false
    }

    if (playlistToConfirm != null) {
        val targetPlaylist = playlistToConfirm!!
        AlertDialog(
            onDismissRequest = { playlistToConfirm = null },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(22.dp),
            icon = {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(BentoAccentPinkText.copy(alpha = 0.15f))
                        .border(1.dp, BentoAccentPinkText.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlaylistRemove,
                        contentDescription = null,
                        tint = BentoAccentPinkText,
                        modifier = Modifier.size(23.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Remove from Playlist?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                Text(
                    text = "Remove \"${song.displayTitle}\" from \"${targetPlaylist.name}\"? The song will remain in your main library.",
                    fontSize = 13.5.sp,
                    color = BentoTextSecondary,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        val pl = targetPlaylist
                        playlistToConfirm = null
                        onRemoveFromPlaylist(pl.id, song.id)
                        Toast.makeText(context, "Removed from ${pl.name}", Toast.LENGTH_SHORT).show()
                        val updated = playlists.filter { it.id != pl.id }
                        playlists = updated
                        if (updated.isEmpty()) {
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoAccentPinkText),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Remove", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToConfirm = null }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = BentoCardSurface,
            border = BorderStroke(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        BentoAccentPinkText.copy(alpha = 0.55f),
                        BentoBorder.copy(alpha = 0.45f)
                    )
                )
            ),
            shadowElevation = 20.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BentoAccentPinkText.copy(alpha = 0.16f))
                                .border(1.dp, BentoAccentPinkText.copy(alpha = 0.38f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlaylistRemove,
                                contentDescription = null,
                                tint = BentoAccentPinkText,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "MANAGE MEMBERSHIP",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoAccentPinkText,
                                letterSpacing = 1.1.sp
                            )
                            Text(
                                text = "Remove from Playlist",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(BentoCardDarkPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Target Song Preview Banner
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardDarkPurple.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtView(
                            song = song,
                            modifier = Modifier.size(44.dp),
                            cornerRadius = 10
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.displayTitle,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = song.displayArtist,
                                fontSize = 12.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (!isLoading && playlists.isNotEmpty()) {
                    Text(
                        text = "Select a playlist below to remove this track from (${playlists.size} ${if (playlists.size == 1) "playlist" else "playlists"}):",
                        fontSize = 12.sp,
                        color = BentoTextSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 70.dp, max = 300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = BentoPrimary,
                            modifier = Modifier.size(34.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else if (playlists.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BentoCardDarkPurple.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 22.dp, horizontal = 16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(BentoPrimary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "\"${song.displayTitle}\" is not currently in any custom playlist.",
                                    fontSize = 13.sp,
                                    color = BentoTextSecondary,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(playlists, key = { it.id }) { playlist ->
                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        playlistToConfirm = playlist
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = BentoCardDarkPurple.copy(alpha = 0.68f),
                                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(11.dp))
                                                .background(BentoPrimary.copy(alpha = 0.15f))
                                                .border(0.5.dp, BentoPrimary.copy(alpha = 0.35f), RoundedCornerShape(11.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!playlist.artworkUri.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = playlist.artworkUri,
                                                    contentDescription = playlist.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                                    contentDescription = null,
                                                    tint = BentoPrimary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = playlist.name,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BentoTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${playlist.songCount} ${if (playlist.songCount == 1) "track" else "tracks"}",
                                                fontSize = 12.sp,
                                                color = BentoTextSecondary
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(BentoAccentPinkText.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.RemoveCircleOutline,
                                                contentDescription = "Remove from ${playlist.name}",
                                                tint = BentoAccentPinkText,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Footer Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!isLoading && playlists.isEmpty()) {
                        Button(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Done", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onDismiss()
                            }
                        ) {
                            Text(
                                text = "Close",
                                color = BentoTextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
