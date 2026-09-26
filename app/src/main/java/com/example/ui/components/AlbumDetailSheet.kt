package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailSheet(
    albumName: String,
    artistName: String,
    albumSongs: List<Song>,
    playbackState: PlaybackState,
    onDismiss: () -> Unit,
    onPlayAll: (Song, List<Song>) -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BentoCardSurface,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)
    ) {
        var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }

        val totalDurationMs = remember(albumSongs) { albumSongs.sumOf { it.durationMs } }
        val totalMins = totalDurationMs / 1000 / 60

        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Album Details",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoPrimary
                )

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = BentoTextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Album Artwork & Title Block
            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BentoCardDarkPurple
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val representativeSong = albumSongs.firstOrNull()
                        AlbumArtView(
                            song = representativeSong,
                            modifier = Modifier.size(80.dp),
                            cornerRadius = 16
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = albumName,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = artistName,
                                fontSize = 14.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${albumSongs.size} tracks • $totalMins mins",
                                fontSize = 12.sp,
                                color = BentoTextSubtle
                            )
                        }

                        var showAlbumMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showAlbumMenu = true }) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.MoreVert,
                                    contentDescription = "Album Options",
                                    tint = BentoTextSecondary
                                )
                            }
                            DropdownMenu(
                                expanded = showAlbumMenu,
                                onDismissRequest = { showAlbumMenu = false },
                                modifier = Modifier.background(BentoCardSurface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Add Album to Queue", color = BentoTextPrimary) },
                                    onClick = {
                                        showAlbumMenu = false
                                        albumSongs.forEach { onAddToQueue(it) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Play Album Next", color = BentoTextPrimary) },
                                    onClick = {
                                        showAlbumMenu = false
                                        albumSongs.reversed().forEach { onPlayNext(it) }
                                    }
                                )
                                if (albumSongs.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Add Album to Playlist", color = BentoTextPrimary) },
                                        onClick = {
                                            showAlbumMenu = false
                                            onAddToPlaylist(albumSongs.first())
                                        }
                                    )
                                }
                            }
                        }
                    }


                    if (albumSongs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { onPlayAll(albumSongs.first(), albumSongs) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play Album",
                                    tint = BentoOnPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play Album", color = BentoOnPrimary, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    if (albumSongs.isNotEmpty()) {
                                        val shuffled = albumSongs.shuffled()
                                        onPlayAll(shuffled.first(), shuffled)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoTextPrimary)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = BentoTextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Shuffle", color = BentoTextPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Song List
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(albumSongs, key = { idx, song -> "${song.id}_$idx" }) { _, song ->
                    Box {
                        SongItem(
                            song = song,
                            isPlaying = playbackState.currentSong?.id == song.id,
                            onClick = { onPlayAll(song, albumSongs) },
                            onFavoriteToggle = { onFavoriteToggle(song) },
                            onMoreClick = { selectedSongForMenu = song }
                        )

                        if (selectedSongForMenu?.id == song.id) {
                            SongActionMenu(
                                expanded = true,
                                song = song,
                                onDismissRequest = { selectedSongForMenu = null },
                                onPlayNow = { onPlayAll(song, albumSongs) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToQueue = { onAddToQueue(song) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onFavoriteToggle = { onFavoriteToggle(song) },
                                onEditTags = if (onEditTags != null) { { onEditTags(song) } } else null,
                                onDeleteSong = { onDeleteSong(song) }
                            )
                        }
                    }
                }
            }
        }
    }
}
