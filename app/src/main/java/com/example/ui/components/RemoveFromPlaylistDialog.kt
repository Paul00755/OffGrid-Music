package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.ui.theme.*

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
                    text = "Are you sure you want to remove \"${song.displayTitle}\" from \"${targetPlaylist.name}\"?",
                    fontSize = 14.sp,
                    color = BentoTextSecondary,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
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
                    colors = ButtonDefaults.buttonColors(containerColor = BentoAccentPinkText)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BentoCardSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text(
                    text = "Remove from Playlist",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
                if (!isLoading && playlists.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Select a playlist to remove \"${song.displayTitle}\" from:",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 340.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = BentoPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                } else if (playlists.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(BentoCardDarkPurple),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "\"${song.displayTitle}\" is not added to any playlists.",
                            fontSize = 14.sp,
                            color = BentoTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = BentoCardDarkPurple),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        playlistToConfirm = playlist
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(BentoPrimary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.QueueMusic,
                                            contentDescription = null,
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${playlist.songCount} ${if (playlist.songCount == 1) "track" else "tracks"}",
                                            fontSize = 12.sp,
                                            color = BentoTextSecondary
                                        )
                                    }

                                    IconButton(
                                        onClick = { playlistToConfirm = playlist },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RemoveCircleOutline,
                                            contentDescription = "Remove",
                                            tint = BentoAccentPinkText,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isLoading && playlists.isEmpty()) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                ) {
                    Text("OK", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Close", color = BentoTextSecondary)
                }
            }
        }
    )
}
