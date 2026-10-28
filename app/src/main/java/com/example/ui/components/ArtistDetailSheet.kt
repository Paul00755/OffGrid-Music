package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailSheet(
    artistName: String,
    artistSongs: List<Song>,
    playbackState: PlaybackState,
    onDismiss: () -> Unit,
    onPlayAll: (Song, List<Song>) -> Unit,
    onFavoriteToggle: (Song) -> Unit,
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onOpenRemoveFromPlaylist: ((Song) -> Unit)? = null,
    onDeleteSong: (Song) -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BentoCardSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }

        val totalDurationMs = remember(artistSongs) { artistSongs.sumOf { it.durationMs } }
        val durationFormatted = remember(totalDurationMs) {
            val totalSeconds = totalDurationMs / 1000
            val minutes = totalSeconds / 60
            val hours = minutes / 60
            if (hours > 0) {
                "${hours}h ${minutes % 60}m"
            } else {
                "${minutes} mins"
            }
        }

        val distinctAlbums = remember(artistSongs) {
            artistSongs.groupBy { it.displayAlbum }
        }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Header with Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Artist",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoPrimary,
                    letterSpacing = 1.sp
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = BentoTextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Artist Hero Banner
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtistAvatar(
                    artistName = artistName,
                    modifier = Modifier.size(80.dp),
                    fallbackArtworkUri = artistSongs.firstOrNull()?.artworkUri
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artistName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${artistSongs.size} tracks • ${distinctAlbums.size} albums • $durationFormatted",
                        fontSize = 12.sp,
                        color = BentoTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons (Play All & Shuffle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (artistSongs.isNotEmpty()) {
                            onPlayAll(artistSongs.first(), artistSongs)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimary,
                        contentColor = BentoOnPrimary
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play All", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Button(
                    onClick = {
                        if (artistSongs.isNotEmpty()) {
                            val shuffled = artistSongs.shuffled()
                            onPlayAll(shuffled.first(), shuffled)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoCardDarkPurple,
                        contentColor = BentoPrimary
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Shuffle", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Albums row if multiple
            if (distinctAlbums.size > 1) {
                Text(
                    text = "Albums (${distinctAlbums.size})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    items(
                        items = distinctAlbums.entries.toList(),
                        key = { (albumName, _) -> "artist_album_$albumName" },
                        contentType = { "artist_album_item" }
                    ) { (albumName, songs) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BentoCardDarkPurple.copy(alpha = 0.6f),
                            modifier = Modifier.clickable {
                                if (songs.isNotEmpty()) {
                                    onPlayAll(songs.first(), songs)
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumArtView(
                                    song = songs.first(),
                                    modifier = Modifier.size(36.dp),
                                    cornerRadius = 6
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = albumName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${songs.size} songs",
                                        fontSize = 10.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "Songs by $artistName",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = BentoTextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Songs List
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(
                    items = artistSongs,
                    key = { index, song -> "${song.id}_$index" },
                    contentType = { _, _ -> "artist_song_item" }
                ) { index, song ->
                    val isPlaying = playbackState.currentSong?.id == song.id
                    BentoCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayAll(song, artistSongs) },
                        backgroundColor = if (isPlaying) BentoCardDarkPurple else BentoCardDarkPurple.copy(alpha = 0.35f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPlaying) BentoPrimary else BentoTextSecondary,
                                modifier = Modifier.width(26.dp)
                            )

                            AlbumArtView(
                                song = song,
                                modifier = Modifier.size(40.dp),
                                cornerRadius = 6
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.displayTitle,
                                    fontSize = 14.sp,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isPlaying) BentoPrimary else BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val songSecs = (song.durationMs / 1000).coerceAtLeast(0)
                                val trackDuration = String.format("%d:%02d", songSecs / 60, songSecs % 60)
                                Text(
                                    text = "${song.displayAlbum} • $trackDuration",
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { selectedSongForMenu = song },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = BentoTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Options Dropdown Menu for a selected track
            selectedSongForMenu?.let { song ->
                SongActionMenu(
                    expanded = true,
                    song = song,
                    onDismissRequest = { selectedSongForMenu = null },
                    onPlayNow = {
                        onPlayAll(song, artistSongs)
                        selectedSongForMenu = null
                    },
                    onPlayNext = {
                        onPlayNext(song)
                        selectedSongForMenu = null
                    },
                    onAddToQueue = {
                        onAddToQueue(song)
                        selectedSongForMenu = null
                    },
                    onAddToPlaylist = {
                        onAddToPlaylist(song)
                        selectedSongForMenu = null
                    },
                    onOpenRemoveFromPlaylist = onOpenRemoveFromPlaylist?.let { cb -> { cb(song) } },
                    onFavoriteToggle = {
                        onFavoriteToggle(song)
                    },
                    onEditTags = if (onEditTags != null) {
                        {
                            onEditTags(song)
                            selectedSongForMenu = null
                        }
                    } else null,
                    onDeleteSong = {
                        onDeleteSong(song)
                        selectedSongForMenu = null
                    }
                )
            }
        }
    }
}
