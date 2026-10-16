package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionMenu(
    expanded: Boolean,
    song: Song,
    isInPlaylist: Boolean = false,
    playlistName: String? = null,
    isInQueue: Boolean = false,
    onDismissRequest: () -> Unit,
    onPlayNow: (() -> Unit)? = null,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenRemoveFromPlaylist: (() -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onMoveToTop: (() -> Unit)? = null,
    onEditTags: (() -> Unit)? = null,
    onDeleteSong: () -> Unit
) {
    if (!expanded) return

    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = BentoCardSurface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header Row with Album Art, Title, Artist, Album
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtView(
                    song = song,
                    modifier = Modifier.size(56.dp),
                    cornerRadius = 12
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.displayTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${song.displayArtist} • ${song.displayAlbum}",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(BentoCardDarkPurple)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = BentoTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(8.dp))

            // Action Items
            if (onPlayNow != null) {
                MenuOptionRow(
                    icon = Icons.Default.PlayArrow,
                    title = "Play Now",
                    iconTint = BentoPrimary,
                    onClick = {
                        onDismissRequest()
                        onPlayNow()
                    }
                )
            }

            MenuOptionRow(
                icon = Icons.Default.SkipNext,
                title = "Play Next",
                iconTint = BentoPrimary,
                onClick = {
                    onDismissRequest()
                    onPlayNext()
                    Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
                }
            )

            MenuOptionRow(
                icon = Icons.Default.QueueMusic,
                title = "Add to Queue",
                iconTint = BentoPrimary,
                onClick = {
                    onDismissRequest()
                    onAddToQueue()
                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                }
            )

            MenuOptionRow(
                icon = Icons.Default.PlaylistAdd,
                title = "Add to Playlist",
                iconTint = BentoPrimary,
                onClick = {
                    onDismissRequest()
                    onAddToPlaylist()
                }
            )

            if (onOpenRemoveFromPlaylist != null) {
                MenuOptionRow(
                    icon = Icons.Default.RemoveCircleOutline,
                    title = "Remove from Playlist",
                    iconTint = BentoAccentPinkText,
                    onClick = {
                        onDismissRequest()
                        onOpenRemoveFromPlaylist()
                    }
                )
            }

            if (onFavoriteToggle != null) {
                MenuOptionRow(
                    icon = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    title = if (song.isFavorite) "Remove from Favorites" else "Add to Favorites",
                    iconTint = if (song.isFavorite) Color(0xFFFF5252) else BentoTextSecondary,
                    onClick = {
                        onDismissRequest()
                        onFavoriteToggle()
                    }
                )
            }

            if (isInPlaylist && onRemoveFromPlaylist != null) {
                MenuOptionRow(
                    icon = Icons.Default.RemoveCircleOutline,
                    title = if (playlistName != null) "Remove from $playlistName" else "Remove from Playlist",
                    iconTint = BentoAccentPinkText,
                    onClick = {
                        onDismissRequest()
                        onRemoveFromPlaylist()
                    }
                )
            }

            if (isInQueue && onMoveToTop != null) {
                MenuOptionRow(
                    icon = Icons.Default.VerticalAlignTop,
                    title = "Move to Top of Up Next",
                    iconTint = BentoPrimary,
                    onClick = {
                        onDismissRequest()
                        onMoveToTop()
                    }
                )
            }

            if (isInQueue && onRemoveFromQueue != null) {
                MenuOptionRow(
                    icon = Icons.Default.RemoveCircleOutline,
                    title = "Remove from Queue",
                    iconTint = BentoAccentPinkText,
                    onClick = {
                        onDismissRequest()
                        onRemoveFromQueue()
                    }
                )
            }

            if (onEditTags != null) {
                MenuOptionRow(
                    icon = Icons.Default.EditNote,
                    title = "Edit Audio Tags & Lyrics",
                    iconTint = BentoPrimary,
                    onClick = {
                        onDismissRequest()
                        onEditTags()
                    }
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))

            MenuOptionRow(
                icon = Icons.Default.Delete,
                title = "Delete Song",
                iconTint = BentoAccentPinkText,
                textColor = BentoAccentPinkText,
                onClick = {
                    showDeleteDialog = true
                }
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete ${song.displayTitle} from your device?", color = BentoTextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "This will permanently delete the audio file from your device storage and remove it from your library, playlists, and queue.",
                        color = BentoTextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteDialog = false
                            onDismissRequest()
                            onDeleteSong()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BentoAccentPinkText)
                    ) {
                        Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel", color = BentoTextSecondary)
                    }
                },
                containerColor = BentoCardSurface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun MenuOptionRow(
    icon: ImageVector,
    title: String,
    iconTint: Color,
    textColor: Color = BentoTextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}
